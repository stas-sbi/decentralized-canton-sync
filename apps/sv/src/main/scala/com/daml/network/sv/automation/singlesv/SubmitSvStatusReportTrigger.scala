// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.splice.dso.svstate.SvStatus
import com.daml.network.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SpliceLedgerConnection,
  TopologyAdminConnection,
}
import com.daml.network.sv.ExtraSynchronizerNode
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.traverse.*
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.topology.DomainId

import java.time.Instant
import scala.util.{Failure, Success}

/** A trigger that regularly submits the status report of the SV to the DSO. */
class SubmitSvStatusReportTrigger(
    svAppConfig: SvAppBackendConfig,
    baseContext: TriggerContext,
    store: SvDsoStore,
    ledgerApiConnection: SpliceLedgerConnection,
    cometBft: Option[CometBftNode],
    mediatorAdminConnectionO: Option[MediatorAdminConnection],
    extraSynchronizerNodes: Map[String, ExtraSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override protected def context: TriggerContext =
    baseContext.copy(config =
      baseContext.config.copy(pollingInterval = svAppConfig.onLedgerStatusReportInterval)
    )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    val svParty = store.key.svParty
    logger.debug(s"Attempting to submit on-ledger SvStatus report...")
    for {
      dsoRules <- store.getDsoRules()
      statusReport <- store.getSvStatusReport(store.key.svParty)
      openMiningRounds <- store.getOpenMiningRoundTriple()
      cometBftHeight <- cometBft.traverse(_.getLatestBlockHeight())
      mediatorAdminConnection = SvUtil.getMediatorAdminConnection(
        dsoRules.domain,
        mediatorAdminConnectionO,
        extraSynchronizerNodes,
      )
      // TODO(#10297): make this code work properly with multiple mediators in the case of soft-domain migration
      mediatorSynchronizerTimeLb <- getDomainTimeLowerBound(
        mediatorAdminConnection,
        dsoRules.domain,
      )
      participantSynchronizerTimeLb <- getDomainTimeLowerBound(
        participantAdminConnection,
        dsoRules.domain,
      )
      now = context.clock.now
      status = new SvStatus(
        now.toInstant,
        // Production deployments always define all of these values, which is why we don't embed the 'Option' value
        // into the status report. We'll only see the magic default values in our tests.
        cometBftHeight.getOrElse[Long](-1L),
        mediatorSynchronizerTimeLb,
        participantSynchronizerTimeLb,
        openMiningRounds.newest.payload.round,
      )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_SubmitStatusReport(
          svParty.toProtoPrimitive,
          statusReport.contractId,
          status,
        )
      )
      _ <- ledgerApiConnection
        .submit(Seq(svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldUnit()
      _ = logger.debug(s"Completed submitting on-ledger SvStatus report.")
    } yield false
  }

  private def getDomainTimeLowerBound(connection: TopologyAdminConnection, domain: DomainId)(
      implicit tc: TraceContext
  ): Future[Instant] = {
    connection
      .getDomainTimeLowerBound(
        domain,
        maxDomainTimeLag = context.config.pollingInterval,
        timeout = SubmitSvStatusReportTrigger.DomainTimeTimeout,
      )
      .transform {
        case Success(ok) =>
          Success(ok.timestamp.toInstant)
        case Failure(ex) =>
          logger.info(s"Failed to get domain time lower bound from ${connection.serviceName}", ex)
          Success(Instant.EPOCH)
      }
  }
}

object SubmitSvStatusReportTrigger {
  val DomainTimeTimeout: NonNegativeDuration = NonNegativeDuration.ofSeconds(15L)
}
