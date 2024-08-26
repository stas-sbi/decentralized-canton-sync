// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.automation.*
import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermission}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** Trigger that promotes participants from observers to submitters for the DSO party.
  *
  * New SV participants hosting the DSO party only receive Observation permission on behalf of the DSO party.
  * This trigger then promotes them to submitter status and respectively updates the Party to Participant threshold
  * once the corresponding SV party has been onboarded to the DsoRules and the ACS offset has no locking state i.e.
  * at least `mediatorReactionTimeout + confirmationResponseTimeout` domain time has elapsed since the participant was made an observer for the DSO Party.
  *
  * Updating the threshold only after the SV party has been added to the DsoRules guarantees that
  * its participant has reconnected to the domain and is ready to confirm transactions on behalf of the DSO party.
  */
class SvOnboardingPromoteParticipantToSubmitterTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    withPromotionDelay: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      SvOnboardingPromoteParticipantToSubmitterTrigger.Task
    ] {

  private val dsoParty = dsoStore.key.dsoParty
  private val svParty = dsoStore.key.svParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SvOnboardingPromoteParticipantToSubmitterTrigger.Task]] = {
    logger.info(
      s"Retrieving tasks with the onboarding participant promotion delay ${if (withPromotionDelay) "enabled"
        else "disabled"}"
    )
    for {
      dsoRules <- dsoStore.getDsoRules()
      dsoHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(dsoRules.domain, dsoParty)
        .map(_.mapping.participants)
      res <-
        if (dsoHostingParticipants.forall(_.permission == ParticipantPermission.Submission)) {
          Future.successful(Seq.empty[SvOnboardingPromoteParticipantToSubmitterTrigger.Task])
        } else if (withPromotionDelay) {
          retrieveTasksWithPromotionDelay(dsoRules)
        } else {
          retrieveTasksWithoutPromotionDelay(dsoHostingParticipants, dsoRules)
        }
    } yield res
  }

  private def retrieveTasksWithoutPromotionDelay(
      dsoHostingParticipants: Seq[HostingParticipant],
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvOnboardingPromoteParticipantToSubmitterTrigger.Task]] = {
    for {
      dsoMemberParticipants <- getDsoMemberParticipants(dsoRules)
    } yield {
      val observingParticipantIds = dsoHostingParticipants
        .filter(_.permission == ParticipantPermission.Observation)
        .map(_.participantId)
      val dsoMemberParticipantIds = dsoMemberParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => dsoMemberParticipantIds.contains(participantId))
        .map(participantId =>
          SvOnboardingPromoteParticipantToSubmitterTrigger.Task(dsoRules.domain, participantId)
        )
    }
  }

  private def retrieveTasksWithPromotionDelay(
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvOnboardingPromoteParticipantToSubmitterTrigger.Task]] = {
    for {
      domainParametersState <- participantAdminConnection.getDomainParametersState(dsoRules.domain)
      domainTimeLowerBound <-
        participantAdminConnection
          .getDomainTimeLowerBound(
            dsoRules.domain,
            maxDomainTimeLag = context.config.pollingInterval,
          )
      dsoHostingParticipants <- participantAdminConnection.listPartyToParticipant(
        filterStore = dsoRules.domain.filterString,
        filterParty = dsoParty.filterString,
        proposals = AuthorizedState,
        timeQuery = TimeQuery.Range(None, None),
      )
      dsoMemberParticipants <- getDsoMemberParticipants(dsoRules)
    } yield {
      val orderedDsoHostingParticipants = dsoHostingParticipants
        .sortBy(
          _.base.validFrom
        ) // redundant sorting to ensure that the last mapping is the most recent one
      val domainParameters = domainParametersState.mapping.parameters
      val delay = domainParameters.mediatorReactionTimeout.duration
        .plus(domainParameters.confirmationResponseTimeout.duration)
      val observingParticipantIds = orderedDsoHostingParticipants.lastOption match {
        case Some(dsoHosting) =>
          dsoHosting.mapping.participants
            .filter(_.permission == ParticipantPermission.Observation)
            .map(_.participantId)
        case _ =>
          Seq.empty[ParticipantId]
      }
      val dsoMemberParticipantIds = dsoMemberParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => dsoMemberParticipantIds.contains(participantId))
        .filter(participantId =>
          // Find the first mapping that added the participant and count from there.
          // Note that this relies on the same participant not being readded which is given by our current onboarding procedures.
          orderedDsoHostingParticipants.find(
            _.mapping.participantIds.contains(participantId)
          ) match {
            case Some(dsoHosting) =>
              domainTimeLowerBound.timestamp.toInstant
                .isAfter(dsoHosting.base.validFrom.plus(delay))
            case _ => false
          }
        )
        .map(participantId =>
          SvOnboardingPromoteParticipantToSubmitterTrigger
            .Task(dsoRules.domain, participantId)
        )
    }
  }

  private def getDsoMemberParticipants(
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules]
  )(implicit tc: TraceContext) = {
    val dsoMembers = dsoRules.contract.payload.svs
      .keySet()
      .asScala
      .map(PartyId.tryFromProtoPrimitive)
      .toSeq
    dsoMembers
      .parTraverse { svParty =>
        participantAdminConnection
          .getPartyToParticipant(dsoRules.domain, svParty)
      }
      .map(_.flatMap(_.mapping.participants))
  }

  override protected def completeTask(
      task: SvOnboardingPromoteParticipantToSubmitterTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    logger.info(
      s"Proposing participant ${task.participantId} be promoted to Submission rights for the DSO party, will wait for it to take effect."
    )
    participantAdminConnection
      .ensureHostingParticipantIsPromotedToSubmitter(
        task.domainId,
        dsoParty,
        task.participantId,
        svParty.uid.namespace.fingerprint,
        RetryFor.ClientCalls,
      )
      .map(_ =>
        TaskSuccess(
          show"Participant ${task.participantId} was promoted to Submission rights for the DSO party"
        )
      )
  }

  override protected def isStaleTask(
      task: SvOnboardingPromoteParticipantToSubmitterTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      dsoHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(dsoRules.domain, dsoParty)
        .map(_.mapping.participants)
    } yield {
      dsoHostingParticipants
        .contains(HostingParticipant(task.participantId, ParticipantPermission.Submission))
    }
  }

}

object SvOnboardingPromoteParticipantToSubmitterTrigger {
  case class Task(domainId: DomainId, participantId: ParticipantId) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domainId", _.domainId), param("participantId", _.participantId))
  }
}
