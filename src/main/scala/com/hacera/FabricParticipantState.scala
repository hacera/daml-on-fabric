// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// (c) 2019 The Unbounded Network LTD

package com.hacera

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.time.Clock
import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Kill, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.daml.ledger.participant.state.kvutils.DamlKvutils._
import com.daml.ledger.participant.state.v1._
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.digitalasset.daml.lf.engine.Engine
import com.digitalasset.daml_lf.DamlLf.Archive
import com.digitalasset.platform.akkastreams.dispatcher.Dispatcher
import com.digitalasset.platform.akkastreams.dispatcher.SubSource.OneAfterAnother
import com.daml.ledger.participant.state.kvutils.{KeyValueCommitting, KeyValueConsumption, KeyValueSubmission, Pretty}
import com.daml.ledger.participant.state.backport.TimeModel
import com.google.protobuf.ByteString
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.time.{Duration => JDuration}
import java.util.concurrent.atomic.AtomicInteger

object FabricParticipantState {

  case class State(
      // Current ledger configuration.
      config: Configuration
  )

  sealed trait Commit extends Serializable with Product

  /** A commit sent to the [[FabricParticipantState.CommitActor]]
    */
  final case class CommitSubmission(
      entryId: DamlLogEntryId,
      submission: DamlSubmission
  ) extends Commit

  /** A periodically emitted heartbeat that is committed to the ledger. */
  final case class CommitHeartbeat(recordTime: Timestamp) extends Commit
}

/** Implementation of the participant-state [[ReadService]] and [[WriteService]] using
  * the key-value utilities and a Fabric store.
  */
class FabricParticipantState(roleTime: Boolean, roleLedger: Boolean, participantId: ParticipantId)(
    implicit system: ActorSystem,
    mat: Materializer
) extends ReadService
    with WriteService
    with AutoCloseable {
  import FabricParticipantState._
  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit private val ec: ExecutionContext = mat.executionContext

  // The ledger configuration
  private val ledgerConfig = Configuration(
    1L,
    TimeModel(JDuration.ofSeconds(600L), JDuration.ofSeconds(600L), JDuration.ofSeconds(600L)).get,
    authorizedParticipantId = Some(participantId),
    openWorld = true
  )

  // DAML Engine for transaction validation.
  private val engine = Engine()

  // Random number generator for generating unique entry identifiers.
  private val rng = new java.util.Random

  // Namespace prefix for log entries.
  private val NS_LOG_ENTRIES = ByteString.copyFromUtf8("L")

  // Namespace prefix for DAML state.
  private val NS_DAML_STATE = ByteString.copyFromUtf8("DS")

  // For an in-memory ledger, an atomic integer is enough to guarantee uniqueness
  private val submissionId = new AtomicInteger()

  /** Interval for heartbeats. Heartbeats are committed to State.commitLog
    * and sent as [[Update.Heartbeat]] to [[stateUpdates]] consumers.
    */
  private val HEARTBEAT_INTERVAL = 5.seconds

  // Fabric connection
  private val fabricConn = com.hacera.DAMLKVConnector.get

  val ledgerId = fabricConn.getLedgerId
  //  val ledgerId = PackageId.assertFromString(UUID.randomUUID.toString)

  /** Reference to the latest state.
    */
  @volatile private var stateRef: State =
  State(
    //commitLog = Vector.empty[Commit],
    //recordTime = Timestamp.Epoch,
    //store = Map.empty[ByteString, ByteString],
    //timeModel = TimeModel.reasonableDefault
    config = Configuration(
      1L,
      timeModel = TimeModel.reasonableDefault,
      authorizedParticipantId = Some(participantId),
      openWorld = true
    )
  )

  private def serializeCommit(commit: Commit): Array[Byte] = {

    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(commit)
    oos.close()
    baos.toByteArray

  }

  private def unserializeCommit(bytes: Array[Byte]): Commit = {

    val bais = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bais)
    val commit = ois.readObject.asInstanceOf[Commit]
    ois.close()
    commit

  }

  /** Akka actor that receives submissions sequentially and
    * commits them one after another to the state, e.g. appending
    * a new ledger commit entry, and applying it to the key-value store.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  class CommitActor extends Actor {

    override def receive: Receive = {
      case commit @ CommitHeartbeat(newRecordTime) =>
        logger.trace(s"CommitActor: committing heartbeat, recordTime=$newRecordTime")

        // Write recordTime to Fabric
        fabricConn.putRecordTime(newRecordTime.toString)

        // Write commit log to Fabric
        val newIndex = fabricConn.putCommit(serializeCommit(commit))

        // if ledger is running, it will read heartbeat back from the chain...
        if (!roleLedger) {
          logger.info(s"Committing new Heartbeat at $newRecordTime")
        }

      case commit @ CommitSubmission(entryId, submission) =>
        val state = stateRef
        val newRecordTime = Timestamp.assertFromString(fabricConn.getRecordTime)

        // check if entry already exists
        val existingEntry = fabricConn.getValue(entryId.getEntryId.toByteArray)
        if (existingEntry != null && existingEntry.nonEmpty) {
          // The entry identifier already in use, drop the message and let the
          // client retry submission.
          logger.warn(s"CommitActor: duplicate entry identifier in commit message, ignoring.")
        } else {
          logger.trace(
            s"CommitActor: processing submission ${Pretty.prettyEntryId(entryId)}..."
          )
          // Process the submission to produce the log entry and the state updates.
          //this.synchronized {
          val (logEntry, damlStateUpdates) = KeyValueCommitting.processSubmission(
            engine,
            entryId,
            newRecordTime,
            state.config,
            submission,
            participantId,
            submission.getInputDamlStateList.asScala
              .map(key => key -> getDamlState(state, key))(breakOut)
          )

          // Combine the abstract log entry and the state updates into concrete updates to the store.
          val allUpdates =
            damlStateUpdates.map {
              case (k, v) =>
                NS_DAML_STATE.concat(KeyValueCommitting.packDamlStateKey(k)) ->
                  KeyValueCommitting.packDamlStateValue(v)
            } + (entryId.getEntryId -> KeyValueCommitting.packDamlLogEntry(logEntry))

          logger.trace(
            s"CommitActor: committing ${Pretty.prettyEntryId(entryId)} and ${allUpdates.size} updates to store."
          )

          // Write some state to Fabric
          for ((k, v) <- allUpdates) {
            fabricConn.putValue(k.toByteArray, v.toByteArray)
          }
          //}

          // Write commit log to Fabric
          val newIndex = fabricConn.putCommit(serializeCommit(commit))

          // Check and write archive
          if (commit.submission.hasPackageUploadEntry) {
            val archives = commit.submission.getPackageUploadEntry.getArchivesList
            archives.forEach { ar =>
              var currentArchives = fabricConn.getPackageList
              if (!currentArchives.contains(ar.getHash)) {
                fabricConn.putPackage(ar.getHash, ar.toByteArray)
              }
            }
          }
        }
    }
  }

  /** Instance of the [[CommitActor]] to which we send messages. */
  private val commitActorRef = {
    // Start the commit actor.
    val actorRef =
      system.actorOf(Props(new CommitActor), s"commit-actor-$ledgerId")

    if (roleTime) {
      // Schedule heartbeat messages to be delivered to the commit actor.
      // This source stops when the actor dies.
      val _ = Source
        .tick(HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, ())
        .map(_ => CommitHeartbeat(getNewRecordTime))
        .to(Sink.actorRef(actorRef, onCompleteMessage = ()))
        .run()
    }

    actorRef
  }

  /** Thread that reads new commits back from Fabric */
  class CommitReader extends Thread {
    override def run(): Unit = {
      var running = true
      var lastHeight = fabricConn.getCommitHeight
      while (running) {

        // we may not use block events directly to generate updates...
        // however, we can check if there are new blocks to not query the chain too often
        if (fabricConn.checkNewBlocks()) {
          val height = fabricConn.getCommitHeight
          if (height > lastHeight) {
            lastHeight = height
            dispatcher.signalNewHead(height)
          }
        }

        try {
          Thread.sleep(50)
        } catch {
          case e: InterruptedException => running = false
        }
      }
    }
  }

  private val commitReaderRef = {
    val threadRef = new CommitReader

    threadRef.start()

    threadRef
  }

  /** The index of the beginning of the commit log */
  private val beginning: Int = fabricConn.getCommitHeight

  if (beginning == 0 && roleTime) {
    // write first ever time into the ledger.. just to make sure that there _is_ time until it starts working
    fabricConn.putRecordTime(getNewRecordTime.toString)
  }

  if (beginning == 0) {
    // now as bad as this is we really need to wait until first record time appears.
    // otherwise, ledger cannot function
    while (fabricConn.getRecordTime == "") {
      Thread.sleep(500)
    }
  }

  /** Dispatcher to subscribe to 'Update' events derived from the state.
    * The index we use here is the "height" of the State.commitLog.
    * This index is transformed into [[Offset]] in [[getUpdate]].
    **
    * [[Dispatcher]] is an utility written by Digital Asset implementing a fanout
    * for a stream of events. It is initialized with an initial offset and a method for
    * retrieving an event given an offset. It provides the method
    * [[Dispatcher.startingAt]] to subscribe to the stream of events from a
    * given offset, and the method [[Dispatcher.signalNewHead]] to signal that
    * new elements has been added.
    */
  private val dispatcher: Dispatcher[Int, List[Update]] = Dispatcher(
    steppingMode = OneAfterAnother(
      (idx: Int, _) => idx + 1,
      (idx: Int) => Future.successful(getUpdate(idx, stateRef))
    ),
    zeroIndex = beginning,
    headAtInitialization = beginning
  )

  // this function retrieves Commit by index
  private def getCommit(idx: Int, state: State): Commit = {
    // read commit from log (stored on Fabric)
    var commitBytes: Array[Byte] = null
    try {
      commitBytes = fabricConn.getCommit(idx)
    } catch {
      case t: Throwable =>
        t.printStackTrace(System.err)
        commitBytes = null
    }

    if (commitBytes == null) {
      sys.error(s"getUpdate: commit index $idx was not found on the ledger")
    }

    val commit = unserializeCommit(commitBytes)
    commit
  }

  /** Helper for [[dispatcher]] to fetch [[DamlLogEntry]] from the
    * state and convert it into [[Update]].
    */
  private def getUpdate(idx: Int, state: State): List[Update] = {

    // read commit from log (stored on Fabric)
    var commitBytes: Array[Byte] = null
    try {
      commitBytes = fabricConn.getCommit(idx)
    } catch {
      case t: Throwable =>
        t.printStackTrace(System.err)
        commitBytes = null
    }

    if (commitBytes == null) {
      sys.error(s"getUpdate: commit index $idx was not found on the ledger")
    }

    val commit = unserializeCommit(commitBytes)

    commit match {
      case CommitSubmission(entryId, _) =>
        // read update from commit: submission
        val updateBytes = fabricConn.getValue(entryId.getEntryId.toByteArray)
        KeyValueConsumption.logEntryToUpdate(
          entryId,
          KeyValueConsumption.unpackDamlLogEntry(ByteString.copyFrom(updateBytes))
        )

      case CommitHeartbeat(recordTime) =>
        // read update from commit: heartbeat
        List(Update.Heartbeat(recordTime))
    }
  }

  /** Subscribe to updates to the participant state.
    * Implemented using the [[Dispatcher]] helper which handles the signalling
    * and fetching of entries from the state.
    *
    * See [[ReadService.stateUpdates]] for full documentation for the properties
    * of this method.
    */
  override def stateUpdates(beginAfter: Option[Offset]): Source[(Offset, Update), NotUsed] =
    dispatcher
      .startingAt(
        beginAfter
          .map(_.components.head.toInt)
          .getOrElse(beginning)
      )
      .collect {
        case (offset, updates) =>
          updates.zipWithIndex.map {
            case (el, idx) => Offset(Array(offset.toLong, idx.toLong)) -> el
          }
      }
      .mapConcat(identity)
      .filter {
        case (offset, _) =>
          if (beginAfter.isDefined)
            offset > beginAfter.get
          else true
      }

  /** Submit a transaction to the ledger.
    *
    * @param submitterInfo: the information provided by the submitter for
    *   correlating this submission with its acceptance or rejection on the
    *   associated [[ReadService]].
    *
    * @param transactionMeta: the meta-data accessible to all consumers of the
    *   transaction. See [[TransactionMeta]] for more information.
    *
    * @param transaction: the submitted transaction. This transaction can
    *   contain contract-ids that are relative to this transaction itself.
    *   These are used to refer to contracts created in the transaction
    *   itself. The participant state implementation is expected to convert
    *   these into absolute contract-ids that are guaranteed to be unique.
    *   This typically happens after a transaction has been assigned a
    *   globally unique id, as then the contract-ids can be derived from that
    *   transaction id.
    *
    * See [[WriteService.submitTransaction]] for full documentation for the properties
    * of this method.
    */
  override def submitTransaction(
      submitterInfo: SubmitterInfo,
      transactionMeta: TransactionMeta,
      transaction: SubmittedTransaction
  ): CompletionStage[SubmissionResult] =
    CompletableFuture.completedFuture({
      // Construct a [[DamlSubmission]] message using the key-value utilities.
      // [[DamlSubmission]] contains the serialized transaction and metadata such as
      // the input contracts and other state required to validate the transaction.
      val submission =
        KeyValueSubmission.transactionToSubmission(submitterInfo, transactionMeta, transaction)

      // Send the [[DamlSubmission]] to the commit actor. The messages are
      // queued and the actor's receive method is invoked sequentially with
      // each message, hence this is safe under concurrency.
      commitActorRef ! CommitSubmission(
        allocateEntryId,
        submission
      )
      SubmissionResult.Acknowledged
    })

  /** Upload DAML-LF packages to the ledger */
  override def uploadPackages(
      archives: List[Archive],
      sourceDescription: Option[String]
  ): CompletionStage[UploadPackagesResult] = {
    val sId = submissionId.getAndIncrement().toString
    val cf = new CompletableFuture[UploadPackagesResult]
    commitActorRef ! CommitSubmission(
      allocateEntryId,
      KeyValueSubmission
        .archivesToSubmission(sId, archives, sourceDescription.getOrElse(""), participantId)
    )
    cf
  }

  /** Retrieve the static initial conditions of the ledger, containing
    * the ledger identifier and the initial ledger record time.
    *
    * Returns a future since the implementation may need to first establish
    * connectivity to the underlying ledger. The implementer may assume that
    * this method is called only once, or very rarely.
    */
  // FIXME(JM): Add configuration to initial conditions!
  override def getLedgerInitialConditions: Source[LedgerInitialConditions, NotUsed] =
    Source.single(initialConditions)

  /** Shutdown by killing the [[CommitActor]]. */
  override def close(): Unit = {
    commitActorRef ! Kill
  }

  private def getLogEntry(state: State, entryId: DamlLogEntryId): DamlLogEntry = {

    val entryBytes = fabricConn.getValue(entryId.getEntryId.toByteArray)
    if (entryBytes.isEmpty)
      return null
    DamlLogEntry.parseFrom(entryBytes)

  }

  private def getDamlState(state: State, key: DamlStateKey): Option[DamlStateValue] = {

    val entryBytes = fabricConn.getValue(
      NS_DAML_STATE.concat(KeyValueCommitting.packDamlStateKey(key)).toByteArray
    )
    if (entryBytes == null || entryBytes.isEmpty)
      return Option[DamlStateValue](null)
    Option[DamlStateValue](DamlStateValue.parseFrom(entryBytes))
  }

  private def allocateEntryId: DamlLogEntryId = {
    val nonce: Array[Byte] = Array.ofDim(8)
    rng.nextBytes(nonce)
    DamlLogEntryId.newBuilder
      .setEntryId(NS_LOG_ENTRIES.concat(ByteString.copyFrom(nonce)))
      .build
  }

  /** The initial conditions of the ledger. The initial record time is the instant
    * at which this class has been instantiated.
    */
  private val initialConditions = LedgerInitialConditions(ledgerId, ledgerConfig, getNewRecordTime)

  /** Get a new record time for the ledger from the system clock.
    * Public for use from integration tests.
    */
  def getNewRecordTime: Timestamp =
    Timestamp.assertFromInstant(Clock.systemUTC().instant())

  /** Allocate a party on the ledger */
  override def allocateParty(
                              hint: Option[String],
                              displayName: Option[String]
                            ): CompletionStage[PartyAllocationResult] =
  // TODO: Implement party management (does not work yet just like in reference)
    CompletableFuture.completedFuture(PartyAllocationResult.NotSupported)

  override def submitConfiguration(maxRecordTime: Timestamp, submissionId: String, config: Configuration): CompletionStage[SubmissionResult] =
    CompletableFuture.completedFuture({
      val submission =
        KeyValueSubmission.configurationToSubmission(maxRecordTime, submissionId, config)
      commitActorRef ! CommitSubmission(
        allocateEntryId,
        submission
      )
      SubmissionResult.Acknowledged
    })
}
