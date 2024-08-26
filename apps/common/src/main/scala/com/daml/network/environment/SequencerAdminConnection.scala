// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment

import cats.implicits.catsSyntaxTuple2Semigroupal
import com.daml.network.admin.api.client.GrpcClientMetrics
import com.daml.network.environment.SequencerAdminConnection.TrafficState
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseSequencerAdminCommands,
  SequencerAdminCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.admin.grpc.InitializeSequencerResponse
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerPruningStatus
import com.digitalasset.canton.health.admin.data.{NodeStatus, SequencerNodeStatus}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.transaction.SequencerDomainStateX
import com.digitalasset.canton.topology.{Member, NodeIdentity, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.traffic.MemberTrafficStatus
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton sequencer admin API that we rely
  * on in our own applications.
  */
class SequencerAdminConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContextExecutor, tracer: Tracer)
    extends TopologyAdminConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    ) {

  override val serviceName = "Canton Sequencer Admin API"

  private val sequencerStatusCommand =
    new StatusAdminCommands.GetStatus(SequencerNodeStatus.fromProtoV30)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[SequencerNodeStatus]] =
    runCmd(
      sequencerStatusCommand
    )

  def getSequencerId(implicit traceContext: TraceContext): Future[SequencerId] =
    getId().map(SequencerId(_))

  def getGenesisState(timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[ByteString] =
    runCmd(
      EnterpriseSequencerAdminCommands.GenesisState(timestamp = Some(timestamp))
    )

  def getOnboardingState(sequencerId: SequencerId)(implicit
      traceContext: TraceContext
  ): Future[ByteString] =
    runCmd(
      EnterpriseSequencerAdminCommands.OnboardingState(Left(sequencerId))
    )

  /** This is used for initializing the sequencer when the domain is first bootstrapped.
    */
  def initializeFromBeginning(
      topologySnapshot: GenericStoredTopologyTransactionsX,
      domainParameters: StaticDomainParameters,
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeFromGenesisState(
        // TODO(#10953) Stop doing that.
        topologySnapshot.toByteString(domainParameters.protocolVersion),
        domainParameters,
      )
    )

  /** This is used for initializing the sequencer after hard domain migrations.
    */
  def initializeFromGenesisState(
      genesisState: ByteString,
      domainParameters: StaticDomainParameters,
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeFromGenesisState(
        genesisState,
        domainParameters,
      )
    )

  def initializeFromOnboardingState(
      onboardingState: ByteString
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeFromOnboardingState(
        onboardingState
      )
    )

  def listSequencerTrafficControlState(filterMembers: Seq[Member] = Seq.empty)(implicit
      traceContext: TraceContext
  ): Future[Seq[TrafficState]] =
    runCmd(
      SequencerAdminCommands.GetTrafficControlState(filterMembers)
    ).map(_.members.map(TrafficState))

  def getSequencerTrafficControlState(
      member: Member
  )(implicit traceContext: TraceContext): Future[TrafficState] = {
    lookupSequencerTrafficControlState(member).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No traffic state found for member ${member}")
          .asRuntimeException()
      )
    )
  }

  def lookupSequencerTrafficControlState(
      member: Member
  )(implicit traceContext: TraceContext): Future[Option[TrafficState]] = {
    listSequencerTrafficControlState(Seq(member)).map {
      case Seq() => None
      case Seq(m) => Some(m)
      case memberList =>
        throw Status.INTERNAL
          .withDescription(
            s"Received more than one traffic status response for member ${member}: ${memberList}"
          )
          .asRuntimeException()
    }
  }

  private def setTrafficControlState(
      member: Member,
      newTotalExtraTrafficLimit: NonNegativeLong,
      serial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[Option[CantonTimestamp]] = {
    runCmd(
      SequencerAdminCommands.SetTrafficBalance(member, serial, newTotalExtraTrafficLimit)
    )
  }

  def getSequencerDomainState()(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerDomainStateX]] = {
    for {
      domainId <- getStatus.map(_.trySuccess.domainId)
      sequencerState <- getSequencerDomainState(domainId)
    } yield sequencerState
  }

  /** Set the traffic state of currentTrafficState.member to a state with
    *
    * serial >= currentTrafficState.nextSerial and extraTrafficLimit == newTotalExtraTrafficLimit
    * as long as currentSequencerState's serial remains unchanged.
    *
    * Fail with a retryable exception in all other cases, so the caller can recompute the target traffic state
    * and retry setting it.
    */
  def setSequencerTrafficControlState(
      currentTrafficState: TrafficState,
      currentSequencerState: TopologyResult[SequencerDomainStateX],
      newTotalExtraTrafficLimit: NonNegativeLong,
      clock: Clock,
      timeout: NonNegativeFiniteDuration,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val msgPrefix =
      s"setting traffic state for ${currentTrafficState.member} to $newTotalExtraTrafficLimit with next serial ${currentTrafficState.nextSerial}:"
    val deadline = clock.now.plus(timeout.asJavaApproximation)
    // There are multiple cases where we need the caller to retry: we (ab)use gRPC Status codes to communicate this.
    def checkSuccessOrAbort(): Future[Option[io.grpc.Status]] = for {
      (sequencerState, trafficState) <- (
        getSequencerDomainState(),
        getSequencerTrafficControlState(currentTrafficState.member),
      ).tupled
    } yield {
      if (
        trafficState.nextSerial == currentTrafficState.nextSerial && sequencerState.base.serial == currentSequencerState.base.serial
      ) {
        val now = clock.now
        if (now.isAfter(deadline)) {
          Some(Status.DEADLINE_EXCEEDED.withDescription(s"$msgPrefix timed out after ${timeout}"))
        } else {
          None // we did not yet manage to advance the traffic state serial, but there's still time left
        }
      } else if (trafficState.extraTrafficLimit == newTotalExtraTrafficLimit) {
        Some(Status.OK)
      } else if (sequencerState.base.serial != currentSequencerState.base.serial) {
        Some(
          Status.ABORTED.withDescription(
            s"$msgPrefix concurrent change of sequencer state serial to ${sequencerState.base.serial} detected"
          )
        )
      } else {
        if (trafficState.nextSerial < currentTrafficState.nextSerial)
          logger.warn(
            s"$msgPrefix unexpected decrease of traffic state serial from ${currentTrafficState.nextSerial} to ${trafficState.nextSerial}"
          )
        Some(
          Status.ABORTED.withDescription(
            s"$msgPrefix traffic state serial changed to ${trafficState.nextSerial} due a concurrent change of the extraTrafficLimit to ${trafficState.extraTrafficLimit}"
          )
        )
      }
    }

    retryProvider
      .ensureThatO(
        RetryFor.Automation,
        "sequencer_traffic_control",
        s"Extra traffic limit for ${currentTrafficState.member} set to $newTotalExtraTrafficLimit with nextSerial ${currentTrafficState.nextSerial}",
        checkSuccessOrAbort(),
        setTrafficControlState(
          currentTrafficState.member,
          newTotalExtraTrafficLimit,
          serial = currentTrafficState.nextSerial,
        ).map(_ => ()),
        logger,
      )
      .flatMap(status =>
        if (status.isOk) Future.unit else Future.failed(status.asRuntimeException())
      )
  }

  def getSequencerPruningStatus()(implicit
      traceContext: TraceContext
  ): Future[SequencerPruningStatus] =
    runCmd(
      SequencerAdminCommands.GetPruningStatus
    )

  def prune(ts: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[String] =
    runCmd(
      EnterpriseSequencerAdminCommands.Prune(ts)
    )

  def disableMember(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Unit] = runCmd(
    EnterpriseSequencerAdminCommands.DisableMember(member)
  )

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getSequencerId

  override def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] = {
    getStatus.map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_) => false
      case NodeStatus.Success(_) => true
    }
  }
}

object SequencerAdminConnection {

  case class TrafficState(status: MemberTrafficStatus) extends PrettyPrinting {
    def member: Member = status.member
    def extraTrafficConsumed: NonNegativeLong = status.trafficState.extraTrafficConsumed
    def extraTrafficLimit: NonNegativeLong =
      status.trafficState.extraTrafficLimit.fold(NonNegativeLong.zero)(_.toNonNegative)
    def nextSerial: PositiveInt = status.balanceSerial.fold(PositiveInt.one)(_.increment)

    override def pretty: Pretty[TrafficState] = prettyOfClass(
      param("member", _.member),
      param("extraTrafficConsumed", _.extraTrafficConsumed),
      param("extraTrafficLimit", _.extraTrafficLimit),
      param("nextSerial", _.nextSerial),
      param("status", _.status),
    )
  }
}
