// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.onboarding

import com.daml.network.config.UpgradesConfig
import com.daml.network.environment.{
  SpliceLedgerClient,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.daml.network.http.HttpClient
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.sv.{ExtraSynchronizerNode, LocalSynchronizerNode}
import com.daml.network.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import com.daml.network.sv.cometbft.{CometBftNode, CometBftRequestSigner}
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvDsoStore, SvStore, SvSvStore}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.jdk.CollectionConverters.*
import io.grpc.Status

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

trait NodeInitializerUtil extends NamedLogging with Spanning with SynchronizerNodeConfigClient {

  protected val config: SvAppBackendConfig
  protected val storage: Storage
  protected val retryProvider: RetryProvider
  protected val clock: Clock
  protected val domainTimeSync: DomainTimeSynchronization
  protected val domainUnpausedSync: DomainUnpausedSynchronization
  protected val participantAdminConnection: ParticipantAdminConnection
  protected val cometBftNode: Option[CometBftNode]
  protected val ledgerClient: SpliceLedgerClient

  protected def newSvStore(
      key: SvStore.Key,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvStore = SvSvStore(
    key,
    storage,
    loggerFactory,
    retryProvider,
    domainMigrationInfo,
    participantId,
  )

  protected def newSvSvAutomationService(
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
      ledgerClient: SpliceLedgerClient,
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
  ) =
    new SvSvAutomationService(
      clock,
      domainTimeSync,
      domainUnpausedSync,
      config,
      svStore,
      dsoStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
    )

  protected def newDsoStore(
      key: SvStore.Key,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvDsoStore = {
    SvDsoStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
    )
  }

  protected def newSvDsoAutomationService(
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
      localSynchronizerNode: Option[LocalSynchronizerNode],
      extraSynchronizerNodes: Map[String, ExtraSynchronizerNode],
      upgradesConfig: UpgradesConfig,
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
      httpClient: HttpClient,
      templateJsonDecoder: TemplateJsonDecoder,
  ) =
    new SvDsoAutomationService(
      clock,
      domainTimeSync,
      domainUnpausedSync,
      config,
      svStore,
      dsoStore,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
      cometBftNode,
      localSynchronizerNode,
      extraSynchronizerNodes,
      upgradesConfig,
      loggerFactory,
    )

  protected def newDsoPartyHosting(
      dsoParty: PartyId
  )(implicit ec: ExecutionContextExecutor) = new DsoPartyHosting(
    participantAdminConnection,
    dsoParty,
    retryProvider,
    loggerFactory,
  )

  protected def rotateGenesisGovernanceKeyForSV1(
      cometBftNode: Option[CometBftNode],
      name: String,
  )(implicit tc: TraceContext): Future[Unit] =
    cometBftNode match {
      case Some(cometBftNode) =>
        cometBftNode.rotateGenesisGovernanceKeyForSV1(name)
      case _ => Future.unit
    }

  protected def ensureCometBftGovernanceKeysAreSet(
      cometBftNode: Option[CometBftNode],
      svParty: PartyId,
      dsoStore: SvDsoStore,
      dsoAutomation: SvDsoAutomationService,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    cometBftNode match {
      case Some(cometBftNode) =>
        for {
          _ <- retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "updated_node_config_dso_state",
            "Governance keys are updated in the dso state",
            for {
              (rulesAndState, synchronizerNodeConfig) <- getCometBftNodeConfigDsoState(
                dsoStore,
                svParty,
              ).getOrElse(throw new RuntimeException("No DSO rules with SV node state found"))
              governanceKeysPubKey = synchronizerNodeConfig match {
                case Some(synchronizerNodeConfig) =>
                  synchronizerNodeConfig.cometBft.governanceKeys.asScala.map(_.pubKey).toSeq
                case None => Seq.empty
              }
              genesisKeysPubKey = CometBftRequestSigner.getGenesisSigner.PublicKeyBase64
              governanceKeyNotUpdatedInDsoState = governanceKeysPubKey.contains(
                genesisKeysPubKey
              )
              _ = if (governanceKeyNotUpdatedInDsoState) {
                for {
                  localSvNodeConfig <- cometBftNode.getLocalNodeConfig()
                  newSvNodeConfig = getNewSynchronizerNodeConfig(
                    synchronizerNodeConfig,
                    localSvNodeConfig,
                  )
                  _ <- updateSynchronizerNodeConfig(
                    rulesAndState,
                    newSvNodeConfig,
                    dsoStore,
                    dsoAutomation.connection,
                  )
                } yield ()
              }
            } yield {
              if (governanceKeyNotUpdatedInDsoState)
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    "New governance keys is not in the dso state"
                  )
                  .asRuntimeException()
            },
            logger,
          )
        } yield ()
      case None => Future.unit
    }
  }

  protected def isOnboardedInDsoRules(
      svcStore: SvDsoStore
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] = for {
    dsoRules <- svcStore.lookupDsoRules()
    isInDsoRulesSvs = dsoRules.exists(
      _.payload.svs.keySet.contains(svcStore.key.svParty.toProtoPrimitive)
    )
  } yield isInDsoRulesSvs

  protected def checkIsInDecentralizedNamespaceAndStartTrigger(
      dsoAutomation: SvDsoAutomationService,
      dsoStore: SvDsoStore,
      domainId: DomainId,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    retryProvider
      .ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "dso_onboard_namespace",
        s"the namespace of ${dsoStore.key.svParty} is part of the decentralized namespace",
        isOnboardedInDecentralizedNamespace(dsoStore), {
          for {
            _ <- participantAdminConnection
              .ensureDecentralizedNamespaceDefinitionProposalAccepted(
                domainId,
                dsoStore.key.dsoParty.uid.namespace,
                dsoStore.key.svParty.uid.namespace,
                dsoStore.key.svParty.uid.namespace.fingerprint,
                RetryFor.WaitingOnInitDependency,
              )
          } yield ()
        },
        logger,
      )
      .map { _ =>
        logger.info(s"Registering namespace membership trigger for ${dsoStore.key.svParty}")
        dsoAutomation.registerSvNamespaceMembershipTrigger()
      }

  private def isOnboardedInDecentralizedNamespace(
      svcStore: SvDsoStore
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] = for {
    dsoRules <- svcStore.lookupDsoRules()
    isMemberOfDecentralizedNamespace <-
      participantAdminConnection
        .getDecentralizedNamespaceDefinition(
          dsoRules
            .map(_.domain)
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription("Domain not found in DsoRules")
                .asRuntimeException()
            ),
          svcStore.key.dsoParty.uid.namespace,
        )
        .map(_.mapping.owners.contains(svcStore.key.svParty.uid.namespace))
  } yield isMemberOfDecentralizedNamespace

}
