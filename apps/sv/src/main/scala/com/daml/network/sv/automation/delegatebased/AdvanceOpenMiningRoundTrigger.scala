// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.splice
import com.daml.network.store.MiningRoundsStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AdvanceOpenMiningRoundTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[AdvanceOpenMiningRoundTrigger.Task]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]] {
  private val store = svTaskContext.dsoStore

  /** Retrieve a batch of tasks that are ready for execution now. */
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AdvanceOpenMiningRoundTrigger.Task]] =
    (for {
      rules <- OptionT(store.lookupAmuletRules())
      rounds <- OptionT(store.lookupOpenMiningRoundTriple())
      if (rounds.readyToAdvanceAt.isBefore(now.toInstant))
      // NOTE: we store the amulet-rules reference in the task, as otherwise its tickDuration and the one that is
      // actually used in the choice might go out of sync
    } yield AdvanceOpenMiningRoundTrigger.Task(rules.contractId, rounds)).value.map(_.toList)

  /** How to process a task. */
  override protected def completeTaskAsDsoDelegate(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val rounds = task.work.openRounds
    for {
      dsoRules <- store.getDsoRules()
      _ = logger.debug(
        s"Starting work as delegate ${dsoRules.payload.dsoDelegate} for ${task.work}"
      )
      amuletPriceVotes <- store.listMemberAmuletPriceVotes()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_AdvanceOpenMiningRounds(
          task.work.amuletRulesId,
          rounds.oldest.contractId,
          rounds.middle.contractId,
          rounds.newest.contractId,
          amuletPriceVotes.map(_.contractId).asJava,
        )
      )
      (offset, _) <- svTaskContext.connection
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          cmd,
        )
        .noDedup
        .yieldResultAndOffset()
    } yield TaskSuccess(
      s"successfully advanced the rounds and archived round ${rounds.oldest.payload.round.number}"
    )
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] = {
    import cats.instances.future.*
    import cats.syntax.traverse.*

    val domainId = task.work.openRounds.domain
    (for {
      // lookupOpenMiningRoundTriple and lookupAmuletRules will yield corrected
      // domains on next task listing if these have been invalidated by
      // domain reassignment
      _ <- OptionT(
        store.multiDomainAcsStore
          .lookupContractByIdOnDomain(splice.amuletrules.AmuletRules.COMPANION)(
            domainId,
            task.work.amuletRulesId,
          )
      )
      _ <- task.work.openRounds.toSeq.traverse(co =>
        OptionT(
          store.multiDomainAcsStore
            .lookupContractByIdOnDomain(splice.round.OpenMiningRound.COMPANION)(
              domainId,
              co.contractId,
            )
        )
      )
    } yield ()).isEmpty
  }
}

object AdvanceOpenMiningRoundTrigger {
  case class Task(
      amuletRulesId: splice.amuletrules.AmuletRules.ContractId,
      openRounds: MiningRoundsStore.OpenMiningRoundTriple,
  ) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("amuletRulesId", _.amuletRulesId), param("openRounds", _.openRounds))
  }
}
