// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.metrics.api.{MetricDoc, MetricName, MetricsContext}
import com.daml.metrics.api.MetricDoc.MetricQualification.Traffic
import com.daml.metrics.api.MetricHandle.Gauge
import com.daml.network.environment.{SpliceMetrics, RetryProvider, TopologyAdminConnection}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.topology.transaction.DomainParametersStateX
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

abstract class DomainUnpausedSynchronization {

  /** Blocks until the domain has a non-zero confirmation request rate
    */
  def waitForDomainUnpaused()(implicit tc: TraceContext): Future[Unit]
}

object DomainUnpausedSynchronization {
  final class NoopDomainUnpausedSynchronization extends DomainUnpausedSynchronization {
    override def waitForDomainUnpaused()(implicit tc: TraceContext): Future[Unit] = Future.unit
  }

  val Noop = new NoopDomainUnpausedSynchronization()
}

final class DomainParamsStore(
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends DomainUnpausedSynchronization
    with AutoCloseable
    with NamedLogging {

  private val metrics: DomainParamsStore.Metrics =
    new DomainParamsStore.Metrics(retryProvider.metricsFactory)

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var state: DomainParamsStore.State = DomainParamsStore.State(
    None,
    None,
  )

  override def waitForDomainUnpaused()(implicit tc: TraceContext): Future[Unit] = {
    val promiseO = checkDomainUnpaused()
    promiseO match {
      case None => Future.unit
      case Some(promise) =>
        retryProvider
          .waitUnlessShutdown(promise.future)
          .failOnShutdownTo {
            Status.UNAVAILABLE
              .withDescription(
                s"Aborted waitForDomainUnpaused, as RetryProvider(${retryProvider.loggerFactory.properties}) is shutting down"
              )
              .asRuntimeException()
          }
    }
  }

  private def checkDomainUnpaused()(implicit tc: TraceContext): Option[Promise[Unit]] = blocking {
    synchronized {
      state.lastParams match {
        // If we are just starting up, try to submit something to not delay startup.
        // We'll block on the next update if the domain is actually paused.
        case None =>
          None
        case Some(lastParams) =>
          val unpaused = isDomainUnpaused(lastParams)
          if (unpaused) {
            None
          } else {
            logger.info(
              show"Domain is currently paused. This is expected during hard domain migrations."
            )
            state.domainUnpausedPromise match {
              case Some(promise) => Some(promise)
              case None =>
                val promise = Promise[Unit]()
                state = state.copy(domainUnpausedPromise = Some(promise))
                Some(promise)
            }
          }
      }
    }
  }

  def ingestDomainParams(
      params: TopologyAdminConnection.TopologyResult[
        DomainParametersStateX
      ]
  )(implicit tc: TraceContext): Future[Unit] = Future {
    val unpaused = isDomainUnpaused(params)
    metrics.confirmationRequestsMaxRate.updateValue(
      params.mapping.parameters.confirmationRequestsMaxRate.value
    )
    blocking {
      synchronized {
        val newState = state.copy(lastParams = Some(params))
        state.domainUnpausedPromise match {
          case None =>
            state = newState
          case Some(promise) =>
            if (unpaused) {
              logger.info(show"Domain is now unpaused")
              promise.success(())
              state = newState.copy(domainUnpausedPromise = None)
            } else {
              logger.info(show"Domain is still paused")
              state = newState
            }
        }
      }
    }
  }

  private def isDomainUnpaused(
      params: TopologyAdminConnection.TopologyResult[DomainParametersStateX]
  ) = params.mapping.parameters.confirmationRequestsMaxRate > NonNegativeInt.zero

  override def close(): Unit = {
    metrics.close()
  }
}

object DomainParamsStore {

  private final case class State(
      lastParams: Option[TopologyAdminConnection.TopologyResult[DomainParametersStateX]],
      domainUnpausedPromise: Option[Promise[Unit]],
  )

  class Metrics(metricsFactory: CantonLabeledMetricsFactory) extends AutoCloseable {

    val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "domain_params_store"

    @MetricDoc.Tag(
      summary = "DynamicDomainParameters.confirmationRequestsMaxRate",
      description =
        "Last known value of DynamicDomainParameters.confirmationRequestsMaxRate on the configured global domain.",
      qualification = Traffic,
    )
    val confirmationRequestsMaxRate: Gauge[Int] =
      metricsFactory.gauge(prefix :+ "confirmation-requests-max-rate", -1)(MetricsContext.Empty)

    override def close() = {
      confirmationRequestsMaxRate.close()
    }
  }
}
