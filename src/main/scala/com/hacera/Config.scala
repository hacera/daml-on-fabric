// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.hacera

import java.io.File

import com.daml.ledger.participant.state.v1.ParticipantId
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.daml.lf.data.Ref.LedgerString
import com.digitalasset.ledger.api.tls.TlsConfiguration
import com.digitalasset.platform.indexer.IndexerStartupMode

final case class Config(
    port: Int,
    portFile: Option[File],
    archiveFiles: List[File],
    maxInboundMessageSize: Int,
    timeProvider: TimeProvider,
    jdbcUrl: String,
    tlsConfig: Option[TlsConfiguration],
    participantId: ParticipantId,
    extraParticipants: Vector[(ParticipantId, Int, String)],
    startupMode: IndexerStartupMode,
    roleLedger: Boolean,
    roleTime: Boolean,
    roleProvision: Boolean,
    roleExplorer: Boolean
) {
  def withTlsConfig(modify: TlsConfiguration => TlsConfiguration): Config =
    copy(tlsConfig = Some(modify(tlsConfig.getOrElse(TlsConfiguration.Empty))))
}

object Config {
  val DefaultMaxInboundMessageSize = 4194304

  def default: Config =
    new Config(
      0,
      None,
      List.empty,
      DefaultMaxInboundMessageSize,
      TimeProvider.UTC,
      "jdbc:h2:mem:daml_on_fabric;db_close_delay=-1;db_close_on_exit=false",
      None,
      LedgerString.assertFromString("fabric-standalone-participant"),
      Vector.empty,
      IndexerStartupMode.MigrateAndStart,
      false,
      false,
      false,
      false
    )
}
