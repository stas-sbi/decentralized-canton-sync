// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.admin.http

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{ContractId, Update}
import com.daml.network.environment.SpliceLedgerConnection.CommandId
import com.daml.network.environment.CommandPriority
import com.daml.network.environment.ledger.api.DedupConfig
import com.daml.network.util.{Contract, DisclosedContracts}
import com.daml.network.http.v0.definitions as d0
import com.daml.network.wallet.{UserWalletManager, UserWalletService}
import com.daml.network.wallet.store.{UserWalletStore, WalletStore}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import com.daml.network.codegen.java.splice.wallet.install as installCodegen
import com.digitalasset.canton.topology.PartyId
import scala.concurrent.{ExecutionContext, Future}
import io.opentelemetry.api.trace.Tracer

trait HttpWalletHandlerUtil extends Spanning with NamedLogging {
  protected val walletManager: UserWalletManager
  protected val store: WalletStore = walletManager.store
  private val validatorParty: PartyId = store.walletKey.validatorParty
  protected val workflowId: String

  protected def listContracts[TCid <: ContractId[T], T <: Template, ResponseT](
      templateCompanion: Contract.Companion.Template[TCid, T],
      user: String,
      mkResponse: Vector[d0.Contract] => ResponseT,
  )(implicit ec: ExecutionContext, traceContext: TraceContext, tracer: Tracer): Future[ResponseT] =
    withSpan(s"$workflowId.listContracts") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        contracts <- userStore.multiDomainAcsStore.listContracts(
          templateCompanion
        )
      } yield mkResponse(contracts.map(_.contract.toHttp).toVector)
    }

  protected def getUserStore(
      user: String
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[UserWalletStore] =
    getUserWallet(user).map(_.store)

  protected def getUserWallet(
      user: String
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[UserWalletService] =
    walletManager
      .lookupUserWallet(user)
      .map(
        _.getOrElse(
          throw Status.NOT_FOUND
            .withDescription(show"User ${user.singleQuoted}")
            .asRuntimeException()
        )
      )

  /** Executes a wallet action by calling a choice on the WalletInstall contract for the given user.
    *
    * The choice is always executed with the validator party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Note: curried syntax helps with type inference
    */
  protected def exerciseWalletAction[Response](
      getUpdate: (
          installCodegen.WalletAppInstall.ContractId,
          UserWalletStore,
      ) => Future[Update[Response]]
  )(
      user: String,
      dedup: Option[(CommandId, DedupConfig)] = None,
      dislosedContracts: DisclosedContracts = DisclosedContracts(),
      priority: CommandPriority = CommandPriority.Low,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Response] = {
    for {
      userWallet <- getUserWallet(user)
      userStore = userWallet.store
      userParty = userStore.key.endUserParty
      // TODO (#4906) pick install based on disclosed contracts' domain IDs
      install <- userStore.getInstall()
      unadornedUpdate <- getUpdate(install.contractId, userStore)
      update = install.exercise(_ => unadornedUpdate)
      result <- dedup match {
        case None =>
          userWallet.connection
            .submit(Seq(validatorParty), Seq(userParty), update, priority = priority)
            .withDisclosedContracts(dislosedContracts)
            .noDedup
            .yieldResult()
        case Some((commandId, dedupConfig)) =>
          userWallet.connection
            .submit(
              Seq(validatorParty),
              Seq(userParty),
              update,
              priority = priority,
            )
            .withDedup(commandId, dedupConfig)
            .withDisclosedContracts(dislosedContracts)
            .yieldResult()
      }
    } yield result
  }
}
