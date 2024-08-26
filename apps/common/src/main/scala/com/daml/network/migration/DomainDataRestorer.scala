// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.migration

import com.daml.network.environment.{BaseLedgerConnection, ParticipantAdminConnection, RetryFor}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.topology.DomainId
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, Future}

class DomainDataRestorer(
    participantAdminConnection: ParticipantAdminConnection,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  /** We assume the domain was not register prior to trying to restore the data.
    */
  def connectDomainAndRestoreData(
      ledgerConnection: BaseLedgerConnection,
      userId: String,
      domainAlias: DomainAlias,
      domainId: DomainId,
      sequencerConnections: SequencerConnections,
      dars: Seq[Dar],
      acsSnapshot: ByteString,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    logger.info("Registering and connecting to new domain")

    // We use user metadata as a dumb storage to track whether we already imported the ACS.
    ledgerConnection
      .lookupUserMetadata(
        userId,
        BaseLedgerConnection.INITIAL_ACS_IMPORT_METADATA_KEY,
      )
      .flatMap {
        case None =>
          val domainConnectionConfig = DomainConnectionConfig(
            domainAlias,
            domainId = Some(domainId),
            sequencerConnections = sequencerConnections,
            manualConnect = false,
            initializeFromTrustedDomain = true,
          )
          // We rely on the calls here being idempotent
          for {
            // Disconnect
            _ <- participantAdminConnection.disconnectFromAllDomains()
            _ <- importDars(dars)
            _ = logger.info("Imported all the dars.")
            _ <-
              participantAdminConnection
                .ensureDomainRegistered(
                  domainConnectionConfig,
                  RetryFor.ClientCalls,
                )
            _ = logger.info("Importing the ACS")
            _ <- importAcs(acsSnapshot)
            _ = logger.info("Imported the ACS")
            _ <- ledgerConnection.ensureUserMetadataAnnotation(
              userId,
              BaseLedgerConnection.INITIAL_ACS_IMPORT_METADATA_KEY,
              "true",
              RetryFor.ClientCalls,
            )
            _ <-
              participantAdminConnection.connectDomain(domainAlias)
          } yield ()
        case Some(_) =>
          logger.info("Domain is already registered and ACS is imported")
          participantAdminConnection.connectDomain(domainAlias)
      }
  }

  private def importAcs(acs: ByteString)(implicit tc: TraceContext) = {
    participantAdminConnection.uploadAcsSnapshot(
      acs
    )
  }

  private def importDars(dars: Seq[Dar])(implicit tc: TraceContext) = {
    // TODO(#5141): allow limit parallel upload once Canton deals with concurrent uploads
    MonadUtil
      .sequentialTraverse(dars.map { dar =>
        UploadablePackage.fromByteString(dar.hash.toHexString, dar.content)
      }) { dar =>
        participantAdminConnection.uploadDarFileLocally(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      }
      .map { _ =>
        ()
      }
  }

}
