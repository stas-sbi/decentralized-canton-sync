// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import cats.syntax.traverse.*
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.topology.DomainId
import com.daml.network.http.v0.definitions
import Contract.Companion
import com.digitalasset.canton.logging.ErrorLoggingContext

final case class ContractWithState[TCid, T](
    contract: Contract[TCid, T],
    state: ContractState,
) extends Contract.Has[TCid, T] {
  def toAssignedContract: Option[AssignedContract[TCid, T]] =
    state match {
      case ContractState.Assigned(domain) => Some(AssignedContract(contract, domain))
      case ContractState.InFlight => None
    }

  def toHttp(implicit elc: ErrorLoggingContext): definitions.ContractWithState =
    definitions.ContractWithState(
      contract.toHttp,
      state.fold(domainId => Some(domainId.toProtoPrimitive), None),
    )
}

object ContractWithState {
  implicit val pretty: Pretty[ContractWithState[?, ?]] = {
    import Pretty.*
    prettyOfClass[ContractWithState[?, ?]](
      param("contract", _.contract),
      param("state", _.state),
    )
  }

  import com.daml.network.http.v0.definitions.{MaybeCachedContract, MaybeCachedContractWithState}

  def handleMaybeCached[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(
      cachedValue: Option[ContractWithState[TCid, T]],
      maybeCached: MaybeCachedContractWithState,
  )(implicit decoder: TemplateJsonDecoder): Either[String, ContractWithState[TCid, T]] = {
    import ContractState.*
    for {
      contract <- Contract.handleMaybeCachedContract(companion)(
        cachedValue map (_.contract),
        MaybeCachedContract(maybeCached.contract),
      )
      state <- maybeCached.domainId.traverse(d => DomainId fromString d map Assigned)
    } yield ContractWithState(contract, state getOrElse InFlight)
  }
}
