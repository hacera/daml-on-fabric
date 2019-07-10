// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// (c) 2019 The Unbounded Network LTD

package com.hacera

import java.io.File

import com.digitalasset.ledger.api.tls.TlsConfiguration

object Cli {

  private val pemConfig = (path: String, config: Config) =>
    config.copy(
      tlsConfig = config.tlsConfig.fold(
        Some(TlsConfiguration(enabled = true, None, Some(new File(path)), None))
      )(c => Some(c.copy(keyFile = Some(new File(path)))))
    )

  private val crtConfig = (path: String, config: Config) =>
    config.copy(
      tlsConfig = config.tlsConfig.fold(
        Some(TlsConfiguration(enabled = true, Some(new File(path)), None, None))
      )(c => Some(c.copy(keyCertChainFile = Some(new File(path)))))
    )

  private val cacrtConfig = (path: String, config: Config) =>
    config.copy(
      tlsConfig = config.tlsConfig.fold(
        Some(TlsConfiguration(enabled = true, None, None, Some(new File(path))))
      )(c => Some(c.copy(trustCertCollectionFile = Some(new File(path)))))
    )

  private val cmdArgParser = new scopt.OptionParser[Config]("daml-on-fabric") {
    head(
      "A fully compliant DAML Ledger API server backed by a Hyperledger Fabric blockchain."
    )
    opt[Int]("port")
      .optional()
      .action((p, c) => c.copy(port = p))
      .text("Server port. If not set, a random port is allocated.")
    opt[File]("port-file")
      .optional()
      .action((f, c) => c.copy(portFile = Some(f)))
      .text(
        "File to write the allocated port number to. Used to inform clients in CI about the allocated port."
      )
    opt[String]("pem")
      .optional()
      .text("TLS: The pem file to be used as the private key.")
      .action(pemConfig)
    opt[String]("crt")
      .optional()
      .text(
        "TLS: The crt file to be used as the cert chain. Required if any other TLS parameters are set."
      )
      .action(crtConfig)
    opt[String]("cacrt")
      .optional()
      .text("TLS: The crt file to be used as the the trusted root CA.")
      .action(cacrtConfig)
    opt[String]("role")
      .text(
        "Role to start the application as. Supported values (may be multiple values separated by a comma):\n" +
          "                         ledger: run a Ledger API service\n" +
          "                         time: run a Time Service\n" +
          "                         provision: set up the chaincode if not present\n" +
          "                         explorer: run a Fabric Block Explorer API."
      )
      .required()
      .action((r, c) => {
        val splitStr = r.toLowerCase.split("\\s*,\\s*")
        c.copy(
          roleLedger = splitStr.contains("ledger"),
          roleProvision = splitStr.contains("provision"),
          roleTime = splitStr.contains("time"),
          roleExplorer = splitStr.contains("explorer")
        )
      })
    opt[String]("participant-id")
      .text("Participant ID for this ledger. Default is 'fabric-single-participant'.")
      .optional()
      .action((i, c) => c.copy(participantId = i))
    arg[File]("[archive]...")
      .unbounded()
      .optional()
      .action((f, c) => c.copy(archiveFiles = f :: c.archiveFiles))
      .text(
        "DAR files to upload. Scenarios are ignored. Note that you don't need to upload the same packages every time the ledger starts."
      )
  }

  def parse(args: Array[String]): Option[Config] =
    cmdArgParser.parse(args, Config.default)
}
