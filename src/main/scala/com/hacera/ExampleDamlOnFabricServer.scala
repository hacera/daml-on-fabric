// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// (c) 2019 The Unbounded Network LTD

package com.hacera

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import com.codahale.metrics.SharedMetricRegistries
import com.daml.ledger.participant.state.v1.{ParticipantId, SubmissionId}
import com.digitalasset.daml.lf.archive.DarReader
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml_lf_dev.DamlLf
import com.digitalasset.daml_lf_dev.DamlLf.Archive
import com.digitalasset.ledger.api.auth.AuthServiceWildcard
import com.digitalasset.platform.apiserver.{ApiServerConfig, StandaloneApiServer}
import com.digitalasset.platform.common.logging.NamedLoggerFactory
import com.digitalasset.platform.indexer.{IndexerConfig, StandaloneIndexerServer}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/** The example server is a fully compliant DAML Ledger API server
  * backed by the in-memory reference index and participant state implementations.
  * Not meant for production, or even development use cases, but for serving as a blueprint
  * for other implementations.
  */
object ExampleDamlOnFabricServer extends App {
  val logger = LoggerFactory.getLogger(this.getClass)

  val config: com.hacera.Config = Cli
    .parse(
      args,
      "daml-on-fabric",
      "A fully compliant DAML Ledger API server backed by Fabric"
    )
    .getOrElse(sys.exit(1))

  // Initialize Fabric connection
  // this will create the singleton instance and establish the connection
  val fabricConn = DAMLKVConnector.get(config.roleProvision, config.roleExplorer)

  // If we only want to provision, exit right after
  if (!config.roleLedger && !config.roleTime && !config.roleExplorer) {
    logger.info("Hyperledger Fabric provisioning complete.")
    System.exit(0)
  }

  // Initialize Akka and log exceptions in flows.
  implicit val system: ActorSystem = ActorSystem("DamlonFabricServer")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withSupervisionStrategy { e =>
        logger.error(s"Supervision caught exception: $e")
        Supervision.Stop
      }
  )

  val participantId: ParticipantId = Ref.LedgerString.assertFromString(config.participantId)
  val ledger = new FabricParticipantState(config.roleTime, config.roleLedger, participantId)

  val readService = ledger
  val writeService = ledger
  val loggerFactory = NamedLoggerFactory.forParticipant(config.participantId)
  val authService = AuthServiceWildcard

  if (config.roleLedger) {
    def archivesFromDar(file: File): List[Archive] = {
      DarReader[Archive]((_, x) => Try(Archive.parseFrom(x)))
        .readArchiveFromFile(file)
        .fold(t => throw new RuntimeException(s"Failed to parse DAR from $file", t), dar => dar.all)
    }

    // Parse packages that are already on the chain.
    // Because we are using ReferenceIndexService, we have to re-upload them
    val currentPackages = fabricConn.getPackageList
    currentPackages.foreach { pkgid =>
      val submissionId = SubmissionId.assertFromString(UUID.randomUUID().toString)
      val archive = DamlLf.Archive.parseFrom(fabricConn.getPackage(pkgid))
      logger.info(s"Found existing archive ${archive.getHash}.")
      ledger.uploadPackages(submissionId, List(archive), Some("uploaded by server"))
    }

    // Parse DAR archives given as command-line arguments and upload them
    // to the ledger using a side-channel.
    config.archiveFiles.foreach { f =>
      val submissionId = SubmissionId.assertFromString(UUID.randomUUID().toString)
      ledger.uploadPackages(submissionId, archivesFromDar(f), Some("uploaded by server"))
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread(() => fabricConn.shutdown()))

  if (config.roleLedger) {

    val indexersF: Future[(AutoCloseable, AutoCloseable)] = for {
      indexerServer <- StandaloneIndexerServer(
        readService,
        IndexerConfig(config.participantId, config.jdbcUrl, config.startupMode),
        loggerFactory,
        SharedMetricRegistries.getOrCreate(s"indexer-${config.participantId}")
      )
      indexServer <- new StandaloneApiServer(
        ApiServerConfig(
          config.participantId,
          config.archiveFiles,
          config.port,
          config.jdbcUrl,
          config.tlsConfig,
          config.timeProvider,
          config.maxInboundMessageSize,
          config.portFile
        ),
        readService,
        writeService,
        authService,
        loggerFactory,
        SharedMetricRegistries.getOrCreate(s"ledger-api-server-${config.participantId}")
      ).start()
    } yield (indexerServer, indexServer)

    val closed = new AtomicBoolean(false)

    def closeServer(): Unit = {
      if (closed.compareAndSet(false, true)) {
        indexersF.foreach {
          case (indexer, indexServer) =>
            indexer.close()
            indexServer.close()
        }
        materializer.shutdown()
        val _ = system.terminate()
      }
    }

    try Runtime.getRuntime.addShutdownHook(new Thread(() => {
      closeServer()
    }))
    catch {
      case NonFatal(t) =>
        logger.error("Shutting down Sandbox application because of initialization error", t)
        closeServer()
    }
  }
}
