// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.hacera

import java.io.File

import com.digitalasset.ledger.api.tls.TlsConfiguration

final case class Config(
    port: Int,
    portFile: Option[File],
    archiveFiles: List[File],
    tlsConfig: Option[TlsConfiguration],
    roleLedger: Boolean,
    roleTime: Boolean,
    roleProvision: Boolean,
    roleExplorer: Boolean
)

object Config {
  def default: Config =
    new Config(0, None, List.empty, None, false, false, false, false)
}
