package services

import javax.inject.Inject
import javax.inject.Singleton

import play.api.Logging

import scala.util.Try
import scala.collection.mutable.HashMap

import im.paideia.Paideia
import im.paideia.PaideiaSession
import im.paideia.util.PaideiaEnv
import im.paideia.util.ConfKeys
import im.paideia.DAOConfigKey
import im.paideia.DAOConfigValueDeserializer
import im.paideia.common.contracts.PaideiaContract
import im.paideia.common.contracts.PaideiaContractSignature
import im.paideia.common.contracts._
import im.paideia.governance.contracts._
import im.paideia.staking.contracts._
import im.paideia.staking.TotalStakingState
import im.paideia.staking.boxes.StakeStateBox
import im.paideia.governance.boxes.ProposalBasicBox
import im.paideia.governance.boxes.ActionSendFundsBasicBox
import im.paideia.governance.boxes.ActionUpdateConfigBox
import im.paideia.governance.VoteRecord
import im.paideia.common.filtering.FilterLeaf
import im.paideia.common.filtering.FilterType
import im.paideia.common.filtering.CompareField

import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.InputBox
import org.ergoplatform.appkit.impl.ErgoTreeContract

import sigma.Coll

import scorex.crypto.hash.Blake2b256

import models.Proposal
import models.CreateSendFundsActionOutput
import models.DaoConfigValueEntry
import models.ProposalVote

import actors.PaideiaStateActor._

/** A DAO treasury's current shortfall, as last observed by PaideiaSyncTask.
  * missingNanoErgs / missingTokens are `needed - found` (i.e. how much more the
  * treasury would need to hold to cover the transaction that failed), never negative.
  * `height` is the sync height at which this shortfall was last observed.
  */
case class TreasuryShortfall(
    missingNanoErgs: Option[Long],
    missingTokens: Map[String, Long],
    height: Int
)

/** Read-only (and read-mostly) Paideia state accessors, moved out of PaideiaStateActor's
  * sync mailbox so API reads no longer queue behind sync's blockchain-event processing.
  * Guarded by a plain ReentrantReadWriteLock instead of an actor mailbox: readers run
  * concurrently with each other, and are only ever blocked by the sync writer (via
  * PaideiaStateActor.receive, which takes the write lock for every mutating message)
  * or by one of the three handlers below that themselves mutate session registries.
  */
@Singleton
class PaideiaStateService @Inject() () extends Logging {

  // Installs the explicit session FIRST, before anything else in the process (this
  // constructor runs at Guice injection time, ahead of PaideiaStateActor's own
  // construction since the actor now depends on this service) has a chance to touch
  // Paideia.default and lazily create an implicit one instead.
  private val paideiaConf = com.typesafe.config.ConfigFactory.load().getConfig("paideia")
  // storeRoot defaults to "." to keep prod paths (daoconfigs/, proposals/,
  // stakingStates/ relative to CWD); optional `paideia.storeRoot` config key overrides.
  private val storeRoot = new java.io.File(
    if (paideiaConf.hasPath("storeRoot")) paideiaConf.getString("storeRoot") else "."
  )
  Paideia.setDefault(PaideiaSession(new PaideiaEnv(paideiaConf), storeRoot))

  // Fair, so a continuous stream of readers can't starve the sync writer and vice versa.
  private val lock = new java.util.concurrent.locks.ReentrantReadWriteLock(true)

  @volatile var syncing: Boolean = true

  /** Per-DAO treasury shortfalls, as observed on the most recently completed
    * PaideiaSyncTask.generateTransactions cycle. Replaced wholesale each cycle (never
    * mutated in place), so a DAO that recovers simply stops appearing in the map - no
    * locking needed for readers since a single reference assignment is atomic and
    * @volatile ensures visibility across threads.
    */
  @volatile private var treasuryShortfalls: Map[String, TreasuryShortfall] = Map.empty

  /** Replaces the entire treasury shortfall registry with the given cycle's findings.
    * Called once per PaideiaSyncTask.generateTransactions cycle.
    */
  def replaceTreasuryShortfalls(m: Map[String, TreasuryShortfall]): Unit =
    treasuryShortfalls = m

  /** The current treasury shortfall for a DAO, if any. Reads only the volatile
    * registry - no read/write lock and no syncing check, so this stays available even
    * while the sync task is still catching up.
    */
  def getTreasuryShortfall(daoKey: String): Option[TreasuryShortfall] =
    treasuryShortfalls.get(daoKey)

  def withWriteLock[T](body: => T): T = {
    lock.writeLock().lock()
    try {
      body
    } finally {
      lock.writeLock().unlock()
    }
  }

  private def withReadLock[T](body: => T): T = {
    lock.readLock().lock()
    try {
      body
    } finally {
      lock.readLock().unlock()
    }
  }

  private def failIfSyncing(): Unit =
    if (syncing)
      throw new Exception(
        "Paideia state is currently syncing, try again some time later."
      )

  def getDAOProposal(g: GetDAOProposal): Try[Proposal] =
    withReadLock {
      Try {
        failIfSyncing()
        val proposalBox = Paideia
          .getBox(
            new FilterLeaf(
              FilterType.FTEQ,
              new ErgoId(
                Paideia
                  .getConfig(g.daoKey)
                  .getArray[Byte](ConfKeys.im_paideia_dao_proposal_tokenid)
              )
                .toString(),
              CompareField.ASSET,
              0
            )
          )
          .find((box: InputBox) =>
            box
              .getRegisters()
              .get(0)
              .getValue()
              .asInstanceOf[Coll[Int]](0) == g.proposalIndex
          )
          .get

        val actions = Paideia
          .getBox(
            new FilterLeaf(
              FilterType.FTEQ,
              new ErgoId(
                Paideia
                  .getConfig(g.daoKey)
                  .getArray[Byte](ConfKeys.im_paideia_dao_action_tokenid)
              )
                .toString(),
              CompareField.ASSET,
              0
            )
          )
          .filter((box: InputBox) =>
            box
              .getRegisters()
              .get(0)
              .getValue()
              .asInstanceOf[Coll[Long]](0) == g.proposalIndex.toLong
          )
          .map(ab => {
            val actionContract = Paideia._actorList.values
              .flatMap(_.contractInstances)
              .toMap
              .get(Blake2b256(ab.getErgoTree().bytes).array.toList)
              .get
            actionContract match {
              case sfa: ActionSendFundsBasic =>
                val actionBox = ActionSendFundsBasicBox.fromInputBox(g.ctx, ab)
                models.SendFundsAction(
                  actionBox.activationTime,
                  actionBox.optionId,
                  actionBox.outputs
                    .map(ob =>
                      CreateSendFundsActionOutput(
                        Address
                          .fromPropositionBytes(
                            NetworkType.MAINNET,
                            ob.propositionBytes.toArray
                          )
                          .toString,
                        ob.value,
                        ob.tokens
                          .map(t => (new ErgoId(t._1.toArray).toString(), t._2))
                          .toArray
                          .toList,
                        List[String]()
                      )
                    )
                    .toList
                )
              case uca: ActionUpdateConfig =>
                val properKnownKeys =
                  DAOConfigKey.knownKeys.map(kv => (kv._1.toList, kv._2))
                val actionBox = ActionUpdateConfigBox.fromInputBox(g.ctx, ab)
                models.UpdateConfigAction(
                  actionBox.optionId,
                  actionBox.activationTime,
                  actionBox.remove
                    .map(dck =>
                      properKnownKeys.get(dck.hashedKey.toList).flatten
                        .getOrElse("Unknown Key")
                    )
                    .toArray,
                  actionBox.update
                    .map(dcv =>
                      DaoConfigValueEntry(
                        properKnownKeys.get(dcv._1.hashedKey.toList).flatten
                          .getOrElse("Unknown Key"),
                        DAOConfigValueDeserializer.getType(dcv._2),
                        DAOConfigValueDeserializer.toString(dcv._2)
                      )
                    )
                    .toArray,
                  actionBox.insert
                    .map(dcv =>
                      DaoConfigValueEntry(
                        properKnownKeys.get(dcv._1.hashedKey.toList).flatten
                          .getOrElse("Unknown Key"),
                        DAOConfigValueDeserializer.getType(dcv._2),
                        DAOConfigValueDeserializer.toString(dcv._2)
                      )
                    )
                    .toArray
                )
              case _ => throw new Exception("Unknown action contract")
            }
          })

        val proposalContract = Paideia.getProposalContract(
          Blake2b256(proposalBox.getErgoTree().bytes).array.toList
        )
        val proposal =
          Paideia.getDAO(g.daoKey).proposals.get(g.proposalIndex).get
        proposalContract match {
          case pb: ProposalBasic =>
            val pbBox = ProposalBasicBox.fromInputBox(g.ctx, proposalBox)
            val voteMap = proposal.votes
              .getMap(pbBox.digestOpt)
              .get
            models.ProposalBasic(
              pbBox.proposalIndex,
              pbBox.name,
              pbBox.endTime,
              pbBox.passed,
              actions,
              pbBox.voteCount.toList,
              proposalBox.getCreationHeight(),
              voteMap.toMap
                .map((kv: (ErgoId, VoteRecord)) =>
                  ProposalVote(kv._1.toString(), kv._2.votes.toList)
                )
                .toList,
              proposalBox.getId().toString()
            )
          case _ => throw new Exception("Unknown proposal type")
        }
      }
    }

  def getDAOProposals(
      g: GetDAOProposals
  ): Try[List[(Int, String, Int, String)]] =
    withReadLock {
      Try {
        failIfSyncing()
        val proposalBoxes = Paideia
          .getBox(
            new FilterLeaf(
              FilterType.FTEQ,
              new ErgoId(
                Paideia
                  .getConfig(g.daoKey)
                  .getArray[Byte](ConfKeys.im_paideia_dao_proposal_tokenid)
              )
                .toString(),
              CompareField.ASSET,
              0
            )
          )

        Paideia
          .getDAO(g.daoKey)
          .proposals
          .values
          .map(p => {
            val pBox = proposalBoxes.find((box: InputBox) =>
              box
                .getRegisters()
                .get(0)
                .getValue()
                .asInstanceOf[Coll[Int]](0) == p.proposalIndex
            )
            (
              p.proposalIndex,
              p.name,
              pBox.map(_.getCreationHeight()).getOrElse(0),
              pBox.map(_.getId().toString()).getOrElse("")
            )
          })
          .toList
          .filter(p => p._3 > 0)
      }
    }

  def getDAOConfig(g: GetDAOConfig): Try[Map[String, Array[Byte]]] =
    withReadLock {
      Try {
        failIfSyncing()
        val properKnownKeys =
          DAOConfigKey.knownKeys.map(kv => (kv._1.toList, kv._2))
        Paideia
          .getConfig(g.daoKey)
          ._config
          .getMap(None)
          .get
          .toMap
          .map(cv =>
            (
              properKnownKeys.get(cv._1.hashedKey.toList).flatten.getOrElse("Unknown key"),
              cv._2
            )
          )
      }
    }

  def getStake(g: GetStake): Try[List[StakeInfo]] =
    withReadLock {
      Try {
        failIfSyncing()

        val stakeStateNFT = new ErgoId(
          Paideia
            .getConfig(g.daoKey)
            .getArray[Byte](ConfKeys.im_paideia_staking_state_tokenid)
        ).toString()
        val latestUtxo = StakeStateBox.fromInputBox(
          g.ctx,
          Paideia.getBox(
            new FilterLeaf[String](
              FilterType.FTEQ,
              stakeStateNFT,
              CompareField.ASSET,
              0
            )
          )(0)
        )
        val stakeMap =
          TotalStakingState(g.daoKey).currentStakingState.stakeRecords
            .getMap(Some(latestUtxo.stateDigest))
            .get
        val partMap =
          TotalStakingState(g.daoKey).currentStakingState.participationRecords
            .getMap(Some(latestUtxo.participationDigest))
            .get
        g.stakeKeys.flatMap(stakeKey => {
          try {
            val key = ErgoId.create(stakeKey)
            Some(
              StakeInfo(
                key.toString(),
                stakeMap.toMap(key),
                partMap.toMap.get(key)
              )
            )
          } catch {
            case _: Throwable => None
          }
        })

      }
    }

  def getDaoStake(g: GetDaoStake): Try[DaoStakeInfo] =
    withReadLock {
      Try {
        failIfSyncing()

        val emission: Long =
          Paideia.getConfig(g.daoKey)(ConfKeys.im_paideia_staking_emission_amount)
        val cycleLength: Long =
          Paideia.getConfig(g.daoKey)(ConfKeys.im_paideia_staking_cyclelength)
        val stakeStateNFT = new ErgoId(
          Paideia
            .getConfig(g.daoKey)
            .getArray[Byte](ConfKeys.im_paideia_staking_state_tokenid)
        ).toString()
        val stakeStateBox = StakeStateBox.fromInputBox(
          g.ctx,
          Paideia.getBox(
            new FilterLeaf[String](
              FilterType.FTEQ,
              stakeStateNFT,
              CompareField.ASSET,
              0
            )
          )(0)
        )
        DaoStakeInfo(
          TotalStakingState(g.daoKey).currentStakingState
            .totalStaked(Some(stakeStateBox.stateDigest)),
          TotalStakingState(g.daoKey).currentStakingState
            .stakers(Some(stakeStateBox.stateDigest)),
          stakeStateBox.profit,
          stakeStateBox.voted,
          stakeStateBox.votedTotal,
          stakeStateBox.nextEmission,
          emission,
          cycleLength
        )
      }
    }

  def getDAOTreasury(g: GetDAOTreasury): Try[String] =
    withWriteLock {
      Try {
        failIfSyncing()
        Treasury(ConfKeys.im_paideia_contracts_treasury, g.daoKey).contract
          .toAddress()
          .toString()
      }
    }

  def getAllDAOs(g: GetAllDAOs): Try[HashMap[String, (String, Int, String)]] =
    withWriteLock {
      Try {
        failIfSyncing()
        HashMap(
          Paideia._daoMap
            .map(d =>
              try {
                val configContract = Config(
                  d._2
                    .config[PaideiaContractSignature](
                      ConfKeys.im_paideia_contracts_config
                    )
                    .withDaoKey(d._2.key)
                )
                val configBox =
                  configContract
                    .boxes(configContract.getUtxoSet.toList(0))
                Some(
                  (
                    d._1,
                    (
                      d._2.config[String](ConfKeys.im_paideia_dao_name),
                      configBox.getCreationHeight(),
                      configBox.getId().toString()
                    )
                  )
                )
              } catch {
                case _: Throwable => None
              }
            )
            .flatten
            .toSeq: _*
        )
      }
    }

  def getContractSignature(
      g: GetContractSignature
  ): Try[PaideiaContract] = {
    // The className branch calls Paideia.instantiateContractInstance, which
    // unconditionally mutates session registries (actorList / contractInstances), so it
    // needs the write lock even though this is a "read" endpoint; the lookup-only branch
    // (by hash/address) only reads existing registries and can run under the read lock.
    if (g.contractClass.isDefined)
      withWriteLock(getContractSignatureBody(g))
    else
      withReadLock(getContractSignatureBody(g))
  }

  private def getContractSignatureBody(
      g: GetContractSignature
  ): Try[PaideiaContract] =
    Try {
      failIfSyncing()
      g.contractClass match {
        case None =>
          Paideia._actorList.values
            .flatMap(_.contractInstances)
            .find(p =>
              g.contractHash match {
                case None =>
                  g.contractAddress match {
                    case None => false
                    case Some(address) =>
                      new ErgoTreeContract(p._2.ergoTree, NetworkType.MAINNET)
                        .toAddress()
                        .toString()
                        .equals(address)
                  }
                case Some(hash) => p._1.sameElements(hash)
              }
            )
            .map(_._2)
            .getOrElse(
              throw new Exception("Unknown contract")
            )
        case Some(className) =>
          Paideia
            .instantiateContractInstance(
              PaideiaContractSignature(
                className = className,
                version = g.contractVersion.get,
                daoKey = g.contractDaoKey.get
              )
            )
      }
    }
}

object PaideiaStateService {

  /** Folds a single exception raised while building this cycle's transactions into a
    * per-daoKey shortfall accumulator, merging with anything already accumulated for
    * that DAO this cycle (a DAO can hit both an ergs and a tokens shortfall, or the
    * same shortfall kind more than once, within one cycle): the max missing ergs is
    * kept, and token maps are merged keeping the max missing amount per token. Any
    * exception that isn't one of the treasury shortfall types passes the accumulator
    * through unchanged.
    */
  def accumulateShortfall(
      acc: Map[String, TreasuryShortfall],
      exception: Throwable,
      height: Int
  ): Map[String, TreasuryShortfall] =
    exception match {
      case e: TreasuryShortfallErgsException =>
        val missing = math.max(0L, e.neededNanoErgs - e.foundNanoErgs)
        val existing =
          acc.getOrElse(e.daoKey, TreasuryShortfall(None, Map.empty, height))
        acc.updated(
          e.daoKey,
          existing.copy(
            missingNanoErgs =
              Some(math.max(missing, existing.missingNanoErgs.getOrElse(0L))),
            height = height
          )
        )
      case e: TreasuryShortfallTokensException =>
        val missing = e.neededTokens
          .map { case (tokenId, needed) =>
            tokenId -> math.max(0L, needed - e.foundTokens.getOrElse(tokenId, 0L))
          }
          .filter { case (_, amount) => amount > 0L }
        val existing =
          acc.getOrElse(e.daoKey, TreasuryShortfall(None, Map.empty, height))
        acc.updated(
          e.daoKey,
          existing.copy(
            missingTokens = mergeMaxTokenMaps(existing.missingTokens, missing),
            height = height
          )
        )
      case _ => acc
    }

  /** Merges two tokenId -> amount maps keeping, per token, the larger of the two
    * amounts (falling back to 0 for a token absent from one side).
    */
  def mergeMaxTokenMaps(
      a: Map[String, Long],
      b: Map[String, Long]
  ): Map[String, Long] =
    (a.keySet ++ b.keySet)
      .map(tokenId => tokenId -> math.max(a.getOrElse(tokenId, 0L), b.getOrElse(tokenId, 0L)))
      .toMap
}
