// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.util

import cats.syntax.either.*
import com.daml.network.codegen.java.splice.wallet.install as walletCodegen
import com.daml.network.environment.*
import com.daml.network.environment.ledger.api.LedgerClient
import com.daml.network.http.v0.definitions.ExternalPartySubmission
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.{ContractState, QueryResult}
import com.daml.network.util.SpliceUtil
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.{UserWalletManager, UserWalletService}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Write.GenerateTransactions.Proposal
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.{SigningPublicKey, v30}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.HexString
import com.google.protobuf.ByteString
import io.grpc.Status

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

private[validator] object ValidatorUtil {

  private def installWalletForUser(
      validatorServiceParty: PartyId,
      endUserParty: PartyId,
      endUserName: String,
      dsoParty: PartyId,
      storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      priority: CommandPriority,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    val store = storeWithIngestion.store
    logger.debug(
      s"Installing wallet for endUserName:$endUserName, endUserParty=$endUserParty, validatorServiceParty=$validatorServiceParty, dsoParty=$dsoParty"
    )
    for {
      _ <- retryProvider.retryForClientCalls(
        "installWalletForUser",
        "installWalletForUser",
        store.lookupWalletInstallByNameWithOffset(endUserName).flatMap {
          case QueryResult(offset, None) =>
            storeWithIngestion.connection
              .submit(
                actAs = Seq(validatorServiceParty, endUserParty),
                readAs = Seq.empty,
                new walletCodegen.WalletAppInstall(
                  dsoParty.toProtoPrimitive,
                  validatorServiceParty.toProtoPrimitive,
                  endUserName,
                  endUserParty.toProtoPrimitive,
                ).create,
                priority,
              )
              .withDedup(
                commandId = SpliceLedgerConnection
                  .CommandId(
                    "com.daml.network.validator.installWalletForUser",
                    Seq(validatorServiceParty),
                    BaseLedgerConnection.sanitizeUserIdToPartyString(endUserName),
                  ),
                deduplicationOffset = offset,
              )
              .withDomainId(domainId)
              .yieldUnit()
          case QueryResult(_, Some(_)) =>
            logger.info(s"WalletAppInstall for $endUserName already exists, skipping")
            Future.successful(())
        },
        logger,
      )
    } yield ()
  }

  def onboard(
      endUserName: String,
      knownParty: Option[PartyId],
      storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
      validatorUserName: String,
      getAmuletRulesDomain: ScanConnection.GetAmuletRulesDomain,
      participantAdminConnection: ParticipantAdminConnection,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[PartyId] = {
    val store = storeWithIngestion.store
    for {
      userPartyId <- knownParty match {
        case Some(party) =>
          storeWithIngestion.connection.createUserWithPrimaryParty(
            endUserName,
            party,
            Seq(),
          )
        case None =>
          storeWithIngestion.connection.getOrAllocateParty(
            endUserName,
            Seq(),
            participantAdminConnection,
          )
      }
      _ <- retryProvider.ensureThatB(
        RetryFor.ClientCalls,
        "onboard_grant_user_rights",
        s"Grant user rights for user $validatorUserName to act as $userPartyId",
        storeWithIngestion.connection
          .getUserActAs(
            validatorUserName
          )
          .map(_.contains(userPartyId)),
        storeWithIngestion.connection.grantUserRights(
          validatorUserName,
          Seq(userPartyId),
          Seq.empty,
        ),
        logger,
      )
      domainId <- getAmuletRulesDomain()(traceContext)
      _ <- installWalletForUser(
        endUserParty = userPartyId,
        endUserName = endUserName,
        validatorServiceParty = store.key.validatorParty,
        dsoParty = store.key.dsoParty,
        storeWithIngestion = storeWithIngestion,
        domainId = domainId,
        retryProvider = retryProvider,
        logger = logger,
        priority = priority,
      )
      // Create validator right contract so validator can collect validator rewards
      _ <- SpliceUtil.createValidatorRight(
        user = userPartyId,
        validator = store.key.validatorParty,
        dso = store.key.dsoParty,
        connection = storeWithIngestion.connection,
        lookupValidatorRightByParty =
          storeWithIngestion.store.lookupValidatorRightByPartyWithOffset,
        domainId = domainId,
        retryProvider = retryProvider,
        logger = logger,
        priority = priority,
      )
    } yield userPartyId
  }

  def createTopologyMappings(
      partyHint: String,
      publicKey: SigningPublicKey,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[TopologyTransaction[TopologyChangeOp, TopologyMapping]]] = {
    val partyId = PartyId.tryCreate(partyHint, fingerprint = publicKey.fingerprint)

    for {
      participantId <- participantAdminConnection.getParticipantId()
      namespaceDelegationMapping = NamespaceDelegation
        .create(
          namespace = partyId.uid.namespace,
          target = publicKey,
          isRootDelegation = true,
        )
        .valueOr(error =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"failed to construct namespace delegation: $error")
            .asRuntimeException()
        )

      // TODO(#14156): change to Confirmation
      partyToParticipantMapping = PartyToParticipant
        .create(
          partyId = partyId,
          domainId = None,
          threshold = PositiveInt.one,
          participants = Seq(
            HostingParticipant(participantId, ParticipantPermission.Submission)
          ),
          groupAddressing = false,
        )
        .valueOr(error =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"failed to construct party to participant mapping: $error")
            .asRuntimeException()
        )
      partyToKeyMapping = PartyToKeyMapping
        .create(
          partyId,
          None,
          PositiveInt.one,
          NonEmpty.mk(Seq, publicKey),
        )
        .valueOr(error =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"failed to construct party to key mapping: $error")
            .asRuntimeException()
        )
      transactions <- participantAdminConnection.generateTransactions(
        Seq(namespaceDelegationMapping, partyToParticipantMapping, partyToKeyMapping)
          .map(mapping =>
            Proposal(
              mapping = mapping,
              store = TopologyStoreId.AuthorizedStore.filterName,
              serial = Some(PositiveInt.one),
            )
          )
      )
    } yield transactions

  }

  def offboard(
      endUserName: String,
      storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
      validatorUserName: String,
      validatorWalletUserName: Option[String],
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    val store = storeWithIngestion.store
    val validatorParty = store.key.validatorParty
    store.lookupInstallByName(endUserName).flatMap {
      case None =>
        // Note: it's OK to skip off-boarding in this case, as on-boarding always creates an install contract first,
        // and off-boarding archives the install contract jointly with the validator right. Thus we can't end up in a
        // situation where there is a stray validator right.
        // TODO(#12556): revisit the above statement in the context of removing the data races wrt validator user rights that do exist
        logger
          .info(s"Skipping off-boarding of $endUserName, as no wallet install contract was found.")
        Future.unit
      case Some(install) =>
        val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
        for {
          _ <-
            if (
              endUserParty == validatorParty ||
              endUserName == validatorUserName ||
              validatorWalletUserName.contains(endUserName)
            ) {
              val msg = s"Tried to offboard the validator's user: $endUserName"
              logger.warn(msg)
              Future.failed(Status.INVALID_ARGUMENT.withDescription(msg).asRuntimeException())
            } else {
              Future.unit
            }
          _ <- retryProvider.retryForClientCalls(
            "offboard_validator",
            "Remove install contract and validator right",
            Future
              .traverse(
                Seq(
                  "install contract" ->
                    store.lookupWalletInstallByNameWithOffset(endUserName),
                  "validator right" ->
                    store.lookupValidatorRightByPartyWithOffset(endUserParty),
                )
              ) { case (explanation, qocws) =>
                qocws
                  .map(_.value.map(_.exercise(_.exerciseArchive())).orElse {
                    logger.debug(s"No $explanation found for user $endUserName, skipping")
                    None
                  })
              }
              .flatMap(_.collect { case Some(archive) => archive } match {
                case archives @ (headArchive +: tailArchives) =>
                  val domainId = headArchive.origin.state match {
                    case assigned @ ContractState.Assigned(domainId)
                        if tailArchives forall (_.origin.state == assigned) =>
                      domainId
                    case _ =>
                      throw Status.FAILED_PRECONDITION
                        .withDescription(
                          s"install and validator right for $endUserName not ready for archival, in states ${archives
                              .map(_.origin.state)}"
                        )
                        .asRuntimeException()
                  }
                  storeWithIngestion.connection
                    .submit(
                      actAs = Seq(
                        store.key.validatorParty,
                        endUserParty,
                      ),
                      readAs = Seq.empty,
                      archives.map(_.update),
                    )
                    .withDomainId(domainId)
                    .noDedup
                    .yieldUnit()
                case _ => Future.unit
              }),
            logger,
            // once the validator's actAs and readAs rights have been revoked,
            // these commands could fail with PERMISSION_DENIED errors (#4425).
            Seq(Status.Code.PERMISSION_DENIED),
          )
        } yield {
          logger.debug(s"User $endUserParty offboarded")
          ()
        }
    }
  }

  def getValidatorWallet(
      validatorStore: ValidatorStore,
      walletManager: UserWalletManager,
  ): Future[UserWalletService] =
    walletManager.lookupEndUserPartyWallet(validatorStore.key.validatorParty) match {
      case None =>
        Future.failed(
          Status.NOT_FOUND
            .withDescription(
              s"No wallet found for validator party ${validatorStore.key.validatorParty}"
            )
            .asRuntimeException()
        )
      case Some(wallet) => Future.successful(wallet)
    }

  def submitAsExternalParty(
      connection: SpliceLedgerConnection,
      submission: ExternalPartySubmission,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Unit] = {
    val senderParty = PartyId.tryFromProtoPrimitive(submission.partyId)
    val signedTxHash = HexString.parseToByteString(submission.signedTxHash) match {
      case Some(hash) => hash
      case None => throw new RuntimeException("Unable to parse signed tx hash")
    }
    val publicKey = signingPublicKeyFromHexEd25119(submission.publicKey)
    for {
      _ <- connection.executeSubmissionAndWait(
        senderParty,
        ByteString.copyFrom(Base64.getDecoder.decode(submission.transaction)),
        Map(
          senderParty ->
            LedgerClient.Signature(
              signedTxHash,
              publicKey.fingerprint,
            )
        ),
      )
    } yield ()
  }

  def signingPublicKeyFromHexEd25119(publicKey: String): SigningPublicKey = {
    val publicKeyBytes = HexString
      .parseToByteString(publicKey)
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(s"Could not decode public key $publicKey as a hex string")
          .asRuntimeException()
      )
    SigningPublicKey
      .fromProtoV30(
        v30.SigningPublicKey(
          v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_RAW,
          publicKeyBytes,
          v30.SigningKeyScheme.SIGNING_KEY_SCHEME_ED25519,
        )
      )
      .valueOr(err =>
        throw Status.INVALID_ARGUMENT
          .withDescription(s"Failed to decode public key: $err")
          .asRuntimeException()
      )
  }
}
