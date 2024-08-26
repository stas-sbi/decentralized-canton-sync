// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet

import com.daml.network.config.AutomationConfig
import com.daml.network.environment.*
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.util.{HasHealth, TemplateJsonDecoder}
import com.daml.network.wallet.automation.UserWalletAutomationService
import com.daml.network.wallet.config.{AutoAcceptTransfersConfig, TreasuryConfig, WalletSweepConfig}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.daml.network.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.ParticipantId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class UserWalletService(
    ledgerClient: SpliceLedgerClient,
    key: UserWalletStore.Key,
    walletManager: UserWalletManager,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    treasuryConfig: TreasuryConfig,
    storage: Storage,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    scanConnection: BftScanConnection,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    walletSweep: Option[WalletSweepConfig],
    autoAcceptTransfers: Option[AutoAcceptTransfersConfig],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    close: CloseContext,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging
    with HasHealth {

  val store: UserWalletStore =
    UserWalletStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
    )

  val treasury: TreasuryService = new TreasuryService(
    // The treasury gets its own connection, and is required to manage waiting for the store on its own.
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory,
      PackageIdResolver.inferFromAmuletRules(clock, scanConnection, loggerFactory),
    ),
    treasuryConfig,
    clock,
    store,
    walletManager,
    retryProvider,
    scanConnection,
    loggerFactory,
  )

  val automation = new UserWalletAutomationService(
    store,
    treasury,
    ledgerClient,
    scanConnection.getAmuletRulesDomain,
    automationConfig,
    clock,
    domainTimeSync,
    domainUnpausedSync,
    scanConnection,
    retryProvider,
    ingestFromParticipantBegin,
    ingestUpdateHistoryFromParticipantBegin,
    loggerFactory,
    validatorTopupConfigO,
    walletSweep,
    autoAcceptTransfers,
  )

  /** The connection to use when submitting commands based on reads from the WalletStore.
    * The submission will wait for the store to ingest the effect of the command before completing the future.
    */
  val connection: SpliceLedgerConnection = automation.connection

  override def isHealthy: Boolean =
    automation.isHealthy && treasury.isHealthy

  override def onClosed(): Unit = {
    // Close treasury early, that will result in it no longer accepting new requests
    // but in-flight requests can complete. If we close the automation first,
    // a task can get stuck forever waiting for store ingestion to complete.
    treasury.close()
    automation.close()
    store.close()
    super.onClosed()
  }
}
