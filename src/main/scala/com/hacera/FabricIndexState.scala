// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// (c) 2019 The Unbounded Network LTD

package com.hacera

import com.daml.ledger.participant.state.v1.{Offset, _}
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Relation.Relation
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.digitalasset.daml.lf.engine.Blinding
import com.digitalasset.daml.lf.lfpackage.Decode
import com.digitalasset.daml.lf.transaction.Node.{NodeCreate, NodeExercises}
import com.digitalasset.daml.lf.transaction.{BlindingInfo, Transaction}
import com.digitalasset.daml.lf.value.Value
import com.digitalasset.daml_lf.DamlLf.Archive
import com.digitalasset.platform.sandbox.stores.ActiveContractsInMemory
import org.slf4j.LoggerFactory

import scala.collection.immutable.TreeMap

final case class FabricIndexState(
                             ledgerId: LedgerId,
                             recordTime: Timestamp,
                             configuration: Configuration,
                             private val updateId: Option[Offset],
                             private val beginning: Option[Offset],
                             // Accepted transactions indexed by offset.
                             txs: TreeMap[Offset, (Update.TransactionAccepted, BlindingInfo)],
                             activeContracts: ActiveContractsInMemory,
                             // Rejected commands indexed by offset.
                             rejections: TreeMap[Offset, Update.CommandRejected],
                             // Uploaded packages.
                             packageKnownTo: Relation[PackageId, Party],
                             hostedParties: Set[Party]) {

  // Fabric connection
  private val fabricConn = com.hacera.DAMLKVConnector.get

  val logger = LoggerFactory.getLogger(this.getClass)

  import FabricIndexState._

  /** Return True if the mandatory fields have been initialized. */
  def initialized: Boolean = {
    return this.updateId.isDefined && this.beginning.isDefined
  }

  private def getIfInitialized[T](x: Option[T]): T =
    x.getOrElse(sys.error("INTERNAL ERROR: State not yet initialized."))

  def getUpdateId: Offset = getIfInitialized(updateId)
  def getBeginning: Offset = getIfInitialized(beginning)

  /** Return a new state with the given update applied or the
    * invariant violation in case that is not possible.
    */
  def tryApply(uId: Offset, u0: Update): Either[InvariantViolation, FabricIndexState] = {
    if (this.updateId.fold(false)(uId <= _))
      Left(NonMonotonicOffset)
    else {
      val state = this.copy(
        updateId = Some(uId),
        beginning = if (this.beginning.isEmpty) Some(uId) else this.beginning
      )
      // apply update to state with new offset
      u0 match {
        case u: Update.Heartbeat =>
          if (this.recordTime <= u.recordTime)
            Right(state.copy(recordTime = u.recordTime))
          else
            Left(NonMonotonicRecordTimeUpdate)

        case u: Update.ConfigurationChanged =>
          Right(state.copy(configuration = u.newConfiguration))

        case u: Update.PartyAddedToParticipant =>
          Right(state.copy(hostedParties = state.hostedParties + u.party))

        case Update.PublicPackageUploaded(archive) =>
          fabricConn.putPackage(archive.getHash, archive.toByteArray)
          Right(state)

        case u: Update.CommandRejected =>
          Right(
            state.copy(
              rejections = rejections + (uId -> u)
            )
          )
        case u: Update.TransactionAccepted =>
          val blindingInfo = Blinding.blind(
            // FIXME(JM): Make Blinding.blind polymorphic.
            u.transaction.asInstanceOf[Transaction.Transaction]
          )
          activeContracts
            .addTransaction(
              let = u.transactionMeta.ledgerEffectiveTime.toInstant,
              transactionId = u.transactionId,
              workflowId = u.transactionMeta.workflowId,
              transaction = u.transaction,
              explicitDisclosure = blindingInfo.explicitDisclosure,
              localImplicitDisclosure = blindingInfo.localImplicitDisclosure,
              globalImplicitDisclosure = blindingInfo.globalImplicitDisclosure
            )
            .fold(_ => Left(SequencingError), { newActiveContracts =>
              Right(
                state.copy(
                  txs = txs + (uId -> ((u, blindingInfo))),
                  activeContracts = newActiveContracts
                ))
            })
      }
    }
  }

  private def consumedContracts(tx: CommittedTransaction): Iterable[Value.AbsoluteContractId] =
    tx.nodes.values
      .collect {
        case e: NodeExercises[
          NodeId,
          Value.AbsoluteContractId,
          Value.VersionedValue[Value.AbsoluteContractId]] if e.consuming =>
          e.targetCoid
      }

  private def createdContracts(tx: CommittedTransaction): Iterable[(
    Value.AbsoluteContractId,
      Value.ContractInst[Value.VersionedValue[Value.AbsoluteContractId]])] =
    tx.nodes.values
      .collect {
        case c: NodeCreate[
          Value.AbsoluteContractId,
          Value.VersionedValue[Value.AbsoluteContractId]] =>
          (c.coid, c.coinst)
      }
}

object FabricIndexState {
  sealed trait InvariantViolation
  case object NonMonotonicRecordTimeUpdate extends InvariantViolation
  case object StateAlreadyInitialized extends InvariantViolation
  case object NonMonotonicOffset extends InvariantViolation
  case object SequencingError extends InvariantViolation

  def initialState(lic: LedgerInitialConditions): FabricIndexState = FabricIndexState(
    ledgerId = lic.ledgerId,
    updateId = None,
    beginning = None,
    configuration = lic.config,
    recordTime = lic.initialRecordTime,
    txs = TreeMap.empty,
    activeContracts = ActiveContractsInMemory.empty,
    rejections = TreeMap.empty,
    packageKnownTo = Map.empty,
    hostedParties = Set.empty
  )

}
