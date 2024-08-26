// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.{DomainId, PartyId}

object ValidatorTables extends AcsTables {

  case class ValidatorAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      userParty: Option[PartyId] = None,
      userName: Option[String] = None,
      providerParty: Option[PartyId] = None,
      validatorParty: Option[PartyId] = None,
      trafficDomainId: Option[DomainId] = None,
      appConfigurationVersion: Option[Long] = None,
      appConfigurationName: Option[String] = None,
      appReleaseVersion: Option[String] = None,
      jsonHash: Option[String] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "user_party" -> userParty,
      "user_name" -> userName.map(lengthLimited),
      "provider_party" -> providerParty,
      "validator_party" -> validatorParty,
      "traffic_domain_id" -> trafficDomainId,
      "app_configuration_version" -> appConfigurationVersion,
      "app_configuration_name" -> appConfigurationName.map(lengthLimited),
      "app_release_version" -> appReleaseVersion.map(lengthLimited),
      "json_hash" -> jsonHash.map(lengthLimited),
    )
  }

  val acsTableName = "validator_acs_store"
}
