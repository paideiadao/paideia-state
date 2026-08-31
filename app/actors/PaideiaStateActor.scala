package actors

import javax.inject.Inject
import org.apache.commons.io.FileUtils

import akka.actor._
import im.paideia.governance.contracts.ProtoDAOProxy
import im.paideia.common.contracts.PaideiaContractSignature
import im.paideia.util.Env
import org.ergoplatform.appkit.RestApiErgoClient
import org.ergoplatform.appkit.OutBox
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import im.paideia.Paideia
import im.paideia.governance.GovernanceType
import im.paideia.staking.contracts.StakeProxy
import im.paideia.staking.contracts.AddStakeProxy
import im.paideia.staking.contracts.UnstakeProxy
import im.paideia.staking.StakeRecord
import org.ergoplatform.appkit.Address
import im.paideia.common.events.PaideiaEvent
import im.paideia.common.events.PaideiaEventResponse
import im.paideia.DAOConfig
import im.paideia.DAO
import im.paideia.util.ConfKeys
import org.ergoplatform.sdk.ErgoId
import im.paideia.common.contracts._
import im.paideia.governance.contracts._
import im.paideia.staking.contracts._
import im.paideia.staking.TotalStakingState
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import play.api.Logging
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.impl.InputBoxImpl
import im.paideia.util.Util
import java.nio.charset.StandardCharsets
import im.paideia.DAOConfigKey
import _root_.im.paideia.DAOConfigValue
import scorex.crypto.authds.ADDigest
import im.paideia.common.filtering.FilterNode
import im.paideia.governance.VoteRecord
import im.paideia.DAOConfigValueSerializer
import play.api.libs.json.Json
import im.paideia.staking.ParticipationRecord
import org.ergoplatform.appkit.ErgoContract
import scala.reflect.io.File
import im.paideia.DAOConfigValueDeserializer
import models.DaoConfigValueEntry
import sigma.Colls
import sigma.Box
import sigma.data.CBox
import im.paideia.staking.transactions.StakeTransaction
import im.paideia.common.transactions.PaideiaTransaction
import im.paideia.staking.transactions.AddStakeTransaction
import im.paideia.governance.transactions.CastVoteTransaction
import im.paideia.governance.transactions.CreateProposalTransaction
import im.paideia.staking.transactions.UnstakeTransaction

object PaideiaStateActor {

  case class CreateDAOBox(
      ctx: BlockchainContextImpl,
      name: String,
      url: String,
      logo: String,
      description: String,
      minProposalTime: Long,
      banner: String,
      bannerEnabled: Boolean,
      footer: String,
      footerEnabled: Boolean,
      theme: String,
      daoGovernanceTokenId: String,
      stakePoolSize: Long,
      governanceType: GovernanceType.Value,
      quorum: Long,
      threshold: Long,
      stakingEmissionAmount: Long,
      stakingEmissionDelay: Byte,
      stakingCycleLength: Long,
      stakingProfitSharePct: Byte,
      userAddress: String,
      pureParticipationWeight: Byte,
      participationWeight: Byte
  )

  case class StakeTransactionRequest(
      ctx: BlockchainContextImpl,
      daoKey: String,
      userAddress: String,
      stakeAmount: Long
  )

  case class AddStakeTransactionRequest(
      ctx: BlockchainContextImpl,
      daoKey: String,
      stakeKey: String,
      userAddress: String,
      addStakeAmount: Long
  )

  case class UnstakeTransactionRequest(
      ctx: BlockchainContextImpl,
      daoKey: String,
      stakeKey: String,
      userAddress: String,
      newStakeRecord: StakeRecord
  )

  case class BlockchainEvent(
      event: PaideiaEvent,
      syncing: Boolean
  )

  /** Sent once by PaideiaSyncTask before anything else. Handled by attempting
    * Paideia.restoreState(stateDir): on success the actor replies Some(height) and the
    * genesis seeding / archive replay is skipped entirely; on failure it replies None
    * after wiping any partial checkpoint state and re-running the genesis seeding path,
    * so the caller falls back to a full archive replay.
    */
  case object Initialize

  /** Sent once per confirmed block (per the checkpoint-interval policy) after all of
    * that block's transactions have been handled. Handled by draining queued AVL+
    * mutations (Paideia.commit()) and writing a checkpoint (Paideia.persistState) at
    * the given height.
    */
  case class CommitBlock(height: Int)

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetStake(
      daoKey: String,
      stakeKeys: List[String],
      ctx: BlockchainContextImpl
  )

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetDaoStake(
      ctx: BlockchainContextImpl,
      daoKey: String
  )

  case class Bootstrap(
      ctx: BlockchainContextImpl,
      stakepoolSize: Long
  )

  trait ProposalAction

  case class CreateProposalTransactionRequest(
      ctx: BlockchainContextImpl,
      daoKey: String,
      name: String,
      endTime: Long,
      actions: Array[ProposalAction],
      voteKey: String,
      userAddress: String
  )

  case class SendFundsActionOutput(
      address: String,
      nergs: Long,
      tokens: List[ErgoToken],
      registers: List[ErgoValue[_]]
  )

  case class SendFundsAction(
      optionId: Int,
      repeats: Int,
      repeatDelay: Long,
      activationTime: Long,
      outputs: Array[SendFundsActionOutput]
  ) extends ProposalAction

  case class UpdateConfigAction(
      optionId: Int,
      activationTime: Long,
      remove: Array[String],
      update: Array[DaoConfigValueEntry],
      insert: Array[DaoConfigValueEntry]
  ) extends ProposalAction

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetAllDAOs()

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetDAOConfig(
      daoKey: String
  )

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetDAOTreasury(
      daoKey: String
  )

  case class CastVoteTransactionRequest(
      ctx: BlockchainContextImpl,
      daoKey: String,
      stakeKey: String,
      proposalIndex: Int,
      votes: Array[Long],
      userAddress: String
  )

  case class StakeInfo(
      stakeKey: String,
      stakeRecord: StakeRecord,
      participationRecord: Option[ParticipationRecord]
  )

  object StakeInfo {
    implicit val stakeRecordJson = Json.format[StakeRecord]
    implicit val participationRecordJson = Json.format[ParticipationRecord]
    implicit val json = Json.format[StakeInfo]
  }

  case class DaoStakeInfo(
      totalStaked: Long,
      stakers: Long,
      profit: Array[Long],
      voted: Long,
      votedTotal: Long,
      nextEmission: Long,
      emission: Long,
      cycleLength: Long
  )

  object DaoStakeInfo {
    implicit val json = Json.format[DaoStakeInfo]
  }

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetContractSignature(
      contractHash: Option[List[Byte]],
      contractAddress: Option[String],
      contractClass: Option[String],
      contractDaoKey: Option[String],
      contractVersion: Option[String]
  )

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetDAOProposals(daoKey: String)

  // handled by services.PaideiaStateService, kept here as shared DTOs
  case class GetDAOProposal(
      ctx: BlockchainContextImpl,
      daoKey: String,
      proposalIndex: Int
  )
}

class PaideiaStateActor @Inject() (service: services.PaideiaStateService)
    extends Actor
    with Logging {
  import PaideiaStateActor._

  /** Directory where confirmed AVL+ state and checkpoint metadata are persisted
    * (Paideia.commit / persistState / restoreState). java.io.File, not the
    * scala.reflect.io.File imported below under the same simple name (used for the
    * plain directory-creation calls in `initiate`) - always fully-qualified here to
    * avoid the clash.
    */
  private val stateDir: java.io.File =
    new java.io.File(Env.conf.getString("stateDir"))

  initiate

  def receive = {
    case c: CreateDAOBox    => sender() ! service.withWriteLock(createDAOBox(c))
    case s: StakeTransactionRequest =>
      sender() ! service.withWriteLock(stakeTransaction(s))
    case a: AddStakeTransactionRequest =>
      sender() ! service.withWriteLock(addStakeTransaction(a))
    case u: UnstakeTransactionRequest =>
      sender() ! service.withWriteLock(unstakeTransaction(u))
    case e: BlockchainEvent => sender() ! service.withWriteLock(handleEvent(e))
    case p: CreateProposalTransactionRequest =>
      sender() ! service.withWriteLock(createProposal(p))
    case b: Bootstrap => sender() ! service.withWriteLock(bootstrap(b))
    case c: CastVoteTransactionRequest =>
      sender() ! service.withWriteLock(castVoteTransaction(c))
    case Initialize      => sender() ! service.withWriteLock(initializeState())
    case c: CommitBlock  => sender() ! service.withWriteLock(commitBlock(c))
  }

  /** Called once, before any sync activity, in response to Initialize. Tries to
    * resume from a checkpoint; only runs the genesis seeding path (and reports None,
    * telling the caller to fall back to a full archive replay) when no usable
    * checkpoint is found.
    */
  def initializeState(): Option[Int] =
    Paideia.restoreState(stateDir) match {
      case Some(height) =>
        logger.info(
          s"""Restored state at height ${height.toString} from ${stateDir.getPath()}, skipping archive replay"""
        )
        Some(height)
      case None =>
        logger.warn(
          s"""No usable checkpoint restored (${Paideia.lastRestoreError
              .getOrElse("no checkpoint")}), falling back to full replay"""
        )
        // restoreState may have opened stores / populated registries before failing;
        // close those handles and wipe daoconfigs/proposals/stakingStates so the
        // failed restore's partial data can't be reused - the archive is the source
        // of truth for a replay.
        Paideia.clearRegistries(closeStores = true)
        // Wipe store contents so a partial restore can't leak into the replay. Contents
        // only: in docker these directories are bind-mount points, and deleting the
        // directory itself (Paideia.clear / FileUtils.deleteDirectory) fails with EBUSY.
        List("daoconfigs", "proposals", "stakingStates").foreach { name =>
          val dir = new java.io.File(name)
          if (dir.isDirectory) FileUtils.cleanDirectory(dir)
        }
        seedGenesis()
        None
    }

  /** Drains queued confirmed AVL+ mutations into the versioned stores and writes a
    * checkpoint (state.json + box files) at `height`. Called once per confirmed block
    * per PaideiaSyncTask's checkpoint-interval policy, never for mempool/rollback
    * events, whose state is ephemeral by design.
    */
  def commitBlock(c: CommitBlock): Try[Unit] = Try {
    Paideia.commit()
    Paideia.persistState(stateDir, c.height)
  }

  def castVoteTransaction(
      c: CastVoteTransactionRequest
  ): Try[PaideiaTransaction] =
    Try {
      if (service.syncing)
        throw new Exception(
          "Paideia state is currently syncing, try again some time later."
        )
      val userAddress = Address.create(c.userAddress)
      CastVoteTransaction(
        c.ctx,
        c.proposalIndex,
        c.stakeKey,
        VoteRecord(c.votes),
        Paideia.getDAO(c.daoKey),
        userAddress,
        userAddress
      )
    }

  def createBox(
      ctx: BlockchainContextImpl,
      address: Address,
      nergs: Long,
      tokens: List[ErgoToken],
      registers: List[ErgoValue[_]]
  ): Box = {
    val boxBuilder = ctx
      .newTxBuilder()
      .outBoxBuilder()
      .contract(address.toErgoContract())
      .value(Math.max(nergs, 1000000L))
    if (tokens.size > 0)
      boxBuilder.tokens(tokens: _*)
    if (registers.size > 0)
      boxBuilder.registers(registers: _*)

    CBox(
      boxBuilder
        .build()
        .convertToInputWith(Util.randomKey, 0.toShort)
        .asInstanceOf[InputBoxImpl]
        .getErgoBox()
    )
  }

  def createProposal(
      c: CreateProposalTransactionRequest
  ): Try[PaideiaTransaction] = Try {
    if (service.syncing)
      throw new Exception(
        "Paideia state is currently syncing, try again some time later."
      )
    val dao = Paideia.getDAO(c.daoKey)
    val originContract = DAOOrigin
      .contractFromConfig[DAOOrigin](
        ConfKeys.im_paideia_contracts_dao,
        dao.key,
        None
      )
    val proposalIndex = Long.MaxValue - originContract
      .boxes(originContract.getUtxoSet.toList(0))
      .getTokens
      .get(1)
      .getValue
    val proposal =
      dao.newProposal(proposalIndex.toInt, c.name)
    val actions = c.actions.map(a =>
      a match {
        case s: SendFundsAction =>
          ActionSendFundsBasic(
            PaideiaContractSignature(version = "1.0.0", daoKey = c.daoKey)
          )
            .box(
              c.ctx,
              proposal.proposalIndex,
              s.optionId,
              s.activationTime,
              s.outputs.map(o =>
                createBox(
                  c.ctx,
                  Address.create(o.address),
                  o.nergs,
                  o.tokens,
                  o.registers
                )
              ),
              s.repeats,
              s.repeatDelay
            )
            .box
        case u: UpdateConfigAction => {
          u.remove.foreach(k =>
            if (
              !Paideia
                .getConfig(c.daoKey)
                ._config
                .lookUpWithDigest(DAOConfigKey(k))(None)
                .response
                .head
                .tryOp
                .get
                .isDefined
            )
              throw new Exception(
                "DAO Config does not contain the key '%s'".format(k)
              )
          )
          u.update.foreach(dcv => {
            if (
              !Paideia
                .getConfig(c.daoKey)
                ._config
                .lookUpWithDigest(DAOConfigKey(dcv.key))(None)
                .response
                .head
                .tryOp
                .get
                .isDefined
            ) {
              val properKnownKeys =
                DAOConfigKey.knownKeys.map(kv => (kv._1.toList, kv._2))
              throw new Exception(
                "DAO Config does not contain the key '%s'".format(
                  dcv.key
                )
              )
            }
            if (
              !(DAOConfigValueDeserializer
                .getType(
                  Paideia
                    .getConfig(c.daoKey)
                    ._config
                    .lookUpWithDigest(DAOConfigKey(dcv.key))(None)
                    .response
                    .head
                    .tryOp
                    .get
                    .get
                )
                .equals(dcv.valueType))
            )
              throw new Exception(
                "Existing type for key '%s', does not match new type".format(
                  dcv.key
                )
              )
            if (
              !(DAOConfigValueDeserializer
                .toString(
                  DAOConfigValueSerializer
                    .fromString(dcv.valueType, dcv.value)
                )
                .equals(dcv.value))
            )
              throw new Exception(
                "Failed to serialize/deserialize value for key '%s' without value shift"
                  .format(
                    dcv.key
                  )
              )
          })
          u.insert.foreach(dcv => {
            if (
              Paideia
                .getConfig(c.daoKey)
                ._config
                .lookUpWithDigest(DAOConfigKey(dcv.key))(None)
                .response
                .head
                .tryOp
                .get
                .isDefined
            )
              throw new Exception(
                "DAO Config already contains the key '%s'".format(dcv.key)
              )
            if (
              !(DAOConfigValueDeserializer
                .toString(
                  DAOConfigValueSerializer
                    .fromString(dcv.valueType, dcv.value)
                )
                .equals(dcv.value))
            )
              throw new Exception(
                "Failed to serialize/deserialize value for key '%s' without value shift"
                  .format(
                    dcv.key
                  )
              )
          })
          ActionUpdateConfig(
            PaideiaContractSignature(version = "1.0.0", daoKey = c.daoKey)
          )
            .box(
              c.ctx,
              proposal.proposalIndex,
              u.optionId,
              u.activationTime,
              u.remove.map(DAOConfigKey(_)).toList,
              u.update
                .map(dcv =>
                  (
                    DAOConfigKey(dcv.key),
                    DAOConfigValueSerializer
                      .fromString(dcv.valueType, dcv.value)
                  )
                )
                .toList,
              u.insert
                .map(dcv =>
                  (
                    DAOConfigKey(dcv.key),
                    DAOConfigValueSerializer
                      .fromString(dcv.valueType, dcv.value)
                  )
                )
                .toList
            )
            .box
        }
      }
    )
    val proposalBox =
      ProposalBasic(
        PaideiaContractSignature(version = "1.0.0", daoKey = c.daoKey)
      )
        .box(
          c.ctx,
          c.name,
          proposal.proposalIndex,
          Array(0L, 0L),
          0L,
          c.endTime,
          -1.toByte
        )
        .box
    val userAddress = Address.create(c.userAddress)
    CreateProposalTransaction(
      c.ctx,
      c.daoKey,
      proposalBox,
      actions,
      c.voteKey,
      userAddress,
      userAddress
    )
  }

  def createDAOBox(c: CreateDAOBox): Try[OutBox] = Try {
    if (service.syncing)
      throw new Exception(
        "Paideia state is currently syncing, try again some time later."
      )
    ProtoDAOProxy(
      Paideia
        .getConfig(Env.paideiaDaoKey)[PaideiaContractSignature](
          ConfKeys.im_paideia_contracts_protodaoproxy
        )
        .withDaoKey(Env.paideiaDaoKey)
    ).box(
      c.ctx,
      Paideia.getConfig(Env.paideiaDaoKey),
      c.name,
      c.daoGovernanceTokenId,
      c.stakePoolSize,
      c.governanceType,
      c.quorum,
      c.threshold,
      c.stakingEmissionAmount,
      c.stakingEmissionDelay,
      c.stakingCycleLength,
      c.stakingProfitSharePct,
      Address.create(c.userAddress),
      c.pureParticipationWeight,
      c.participationWeight,
      c.url,
      c.description,
      c.logo,
      c.minProposalTime,
      c.banner,
      c.bannerEnabled,
      c.footer,
      c.footerEnabled,
      c.theme
    ).outBox
  }

  def stakeTransaction(s: StakeTransactionRequest): Try[PaideiaTransaction] =
    Try {
      if (service.syncing)
        throw new Exception(
          "Paideia state is currently syncing, try again some time later."
        )
      val userAddress = Address.create(s.userAddress)
      StakeTransaction(s.ctx, s.stakeAmount, userAddress, userAddress, s.daoKey)
    }

  def addStakeTransaction(
      a: AddStakeTransactionRequest
  ): Try[PaideiaTransaction] = Try {
    if (service.syncing)
      throw new Exception(
        "Paideia state is currently syncing, try again some time later."
      )
    val userAddress = Address.create(a.userAddress)
    AddStakeTransaction(
      a.ctx,
      a.addStakeAmount,
      a.stakeKey,
      userAddress,
      userAddress,
      a.daoKey,
      null
    )
  }

  def unstakeTransaction(
      u: UnstakeTransactionRequest
  ): Try[PaideiaTransaction] = Try {
    if (service.syncing)
      throw new Exception(
        "Paideia state is currently syncing, try again some time later."
      )
    val userAddress = Address.create(u.userAddress)
    UnstakeTransaction(
      u.ctx,
      u.stakeKey,
      Colls.fromArray(u.newStakeRecord.toBytes),
      userAddress,
      userAddress,
      u.daoKey,
      null
    )
  }

  def handleEvent(e: BlockchainEvent): Try[PaideiaEventResponse] =
    Try {
      service.syncing = e.syncing
      Paideia.handleEvent(e.event)
    }

  def bootstrap(b: Bootstrap): Array[OutBox] = {
    val stakeBox =
      StakeState(ConfKeys.im_paideia_contracts_staking_state, Env.paideiaDaoKey)
        .emptyBox(b.ctx, Paideia.getDAO(Env.paideiaDaoKey), b.stakepoolSize)
    Array(
      PaideiaOrigin(
        ConfKeys.im_paideia_contracts_paideia_origin,
        Env.paideiaDaoKey
      )
        .box(b.ctx, Paideia.getConfig(Env.paideiaDaoKey), Long.MaxValue - 1L)
        .outBox,
      DAOOrigin(ConfKeys.im_paideia_contracts_dao, Env.paideiaDaoKey)
        .box(
          b.ctx,
          Paideia.getDAO(Env.paideiaDaoKey),
          Long.MaxValue,
          Long.MaxValue
        )
        .outBox,
      Config(ConfKeys.im_paideia_contracts_config, Env.paideiaDaoKey)
        .box(
          b.ctx,
          Paideia.getDAO(Env.paideiaDaoKey),
          Some(Paideia.getConfig(Env.paideiaDaoKey)._config.digest)
        )
        .outBox,
      ChangeStake(
        ConfKeys.im_paideia_contracts_staking_changestake,
        Env.paideiaDaoKey
      )
        .box(b.ctx)
        .outBox,
      Stake(ConfKeys.im_paideia_contracts_staking_stake, Env.paideiaDaoKey)
        .box(b.ctx, 1000000L)
        .outBox,
      Unstake(ConfKeys.im_paideia_contracts_staking_unstake, Env.paideiaDaoKey)
        .box(b.ctx)
        .outBox,
      StakeSnapshot(
        ConfKeys.im_paideia_contracts_staking_snapshot,
        Env.paideiaDaoKey
      )
        .box(b.ctx)
        .outBox,
      StakeCompound(
        ConfKeys.im_paideia_contracts_staking_compound,
        Env.paideiaDaoKey
      )
        .box(b.ctx)
        .outBox,
      StakeVote(ConfKeys.im_paideia_contracts_staking_vote, Env.paideiaDaoKey)
        .box(b.ctx)
        .outBox,
      StakeProfitShare(
        ConfKeys.im_paideia_contracts_staking_profitshare,
        Env.paideiaDaoKey
      )
        .box(b.ctx)
        .outBox,
      stakeBox.outBox
    )
  }

  def initiate = {
    val daoconfigdir = File("daoconfigs").toAbsolute.toDirectory

    if (!daoconfigdir.exists) {
      daoconfigdir.createDirectory()
      logger.info("Created daoconfigdir")
    }

    val stakingStatesDir = File("stakingStates").toDirectory

    if (!stakingStatesDir.exists) {
      val res = stakingStatesDir.createDirectory(true, true)
      stakingStatesDir.createDirectory(true, true)
      logger.info(f"Created stakingStatesDir: ${res.toString()}")
    }

    val proposalsDir = File("proposals").toAbsolute.toDirectory

    if (!proposalsDir.exists) {
      proposalsDir.createDirectory()
      logger.info("Created proposalsDir")
    }
  }

  /** Seeds the Paideia DAO config tree from genesis values and instantiates every
    * default contract signature, exactly as `initiate` used to do unconditionally.
    * Now only run when Initialize finds no usable checkpoint to restore from - sync
    * then replays the whole archive on top of this seed, same as before persistence
    * existed. Every `set` call and the dummy-DAO dance are unchanged, byte-for-byte,
    * from the original `initiate`.
    */
  def seedGenesis(): Unit = {
    val paideiaConfig = DAOConfig(Env.paideiaDaoKey)
    val dummyDaoKey =
      "678441d2c6f7254e6b2f317e45989b42ec3dcd33835b4b03b7c61e9fcc80769c"
    Paideia.addDAO(DAO(dummyDaoKey, paideiaConfig))
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_name,
      Env.conf.getString("im_paideia_dao_name")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_quorum,
      Env.conf.getLong("im_paideia_dao_quorum")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_threshold,
      Env.conf.getLong("im_paideia_dao_threshold")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_tokenid,
      ErgoId
        .create(Env.paideiaTokenId)
        .getBytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_weight_participation,
      Env.conf.getLong("im_paideia_staking_weight_participation").toByte
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_weight_pureparticipation,
      Env.conf.getLong("im_paideia_staking_weight_pureparticipation").toByte
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_cyclelength,
      Env.conf.getLong("im_paideia_staking_cyclelength")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_emission_amount,
      Env.conf.getLong("im_paideia_staking_emission_amount")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_emission_delay,
      Env.conf.getLong("im_paideia_staking_emission_delay")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_profit_share_pct,
      Env.conf.getLong("im_paideia_staking_profit_share_pct").toByte
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_state_tokenid,
      ErgoId
        .create(Env.conf.getString("im_paideia_staking_state_tokenid"))
        .getBytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_createdao_erg,
      Env.conf.getLong("im_paideia_fees_createdao_erg")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_min_proposal_time,
      Env.conf.getLong("im_paideia_dao_min_proposal_time")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_createdao_paideia,
      Env.conf.getLong("im_paideia_fees_createdao_paideia")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_key,
      ErgoId.create(Env.paideiaDaoKey).getBytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_action_tokenid,
      ErgoId
        .create(Env.conf.getString("im_paideia_dao_action_tokenid"))
        .getBytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_proposal_tokenid,
      ErgoId
        .create(Env.conf.getString("im_paideia_dao_proposal_tokenid"))
        .getBytes
    )
    Paideia.addDAO(DAO(Env.paideiaDaoKey, paideiaConfig))
    logger.info(Colls.fromArray(paideiaConfig._config.digest).toString())
    val configContract = Config(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val paideiaOriginContract = PaideiaOrigin(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val protoDaoProxyContract = ProtoDAOProxy(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val treasuryContract = Treasury(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val protoDAOContract = ProtoDAO(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val mintContract = Mint(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val daoContract = DAOOrigin(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    val splitProfitContract = SplitProfit(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_treasury,
      treasuryContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_paideia_origin,
      paideiaOriginContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_protodao,
      protoDAOContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_protodaoproxy,
      protoDaoProxyContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_mint,
      mintContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_config,
      configContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_dao,
      daoContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_split_profit,
      splitProfitContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_createproposal_paideia,
      Env.conf.getLong("im_paideia_fees_createproposal_paideia")
    )

    val defaultTreasuryContract = Treasury(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_treasury,
      treasuryContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_treasury_signature,
      treasuryContract.contractSignature
    )
    val defaultConfigContract = Config(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_config,
      configContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_config_signature,
      configContract.contractSignature
    )
    val defaultActionSendFundsContract = ActionSendFundsBasic(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_action_sendfunds,
      defaultActionSendFundsContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_action_sendfunds_signature,
      defaultActionSendFundsContract.contractSignature
    )
    val defaultActionUpdateConfigContract = ActionUpdateConfig(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_action_updateconfig,
      defaultActionUpdateConfigContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_action_updateconfig_signature,
      defaultActionUpdateConfigContract.contractSignature
    )
    val defaultProposalBasicContract = ProposalBasic(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_proposal_basic,
      defaultProposalBasicContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_proposal_basic_signature,
      defaultProposalBasicContract.contractSignature
    )
    val defaultStakingChangeContract = ChangeStake(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_change,
      defaultStakingChangeContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_change_signature,
      defaultStakingChangeContract.contractSignature
    )
    val defaultStakingStakeContract = Stake(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_stake,
      defaultStakingStakeContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_stake_signature,
      defaultStakingStakeContract.contractSignature
    )
    val defaultStakingCompoundContract = StakeCompound(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_compound,
      defaultStakingCompoundContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_compound_signature,
      defaultStakingCompoundContract.contractSignature
    )
    val defaultStakingProfitshareContract = StakeProfitShare(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_profitshare,
      defaultStakingProfitshareContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_profitshare_signature,
      defaultStakingProfitshareContract.contractSignature
    )
    val defaultStakingSnapshotContract = StakeSnapshot(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_snapshot,
      defaultStakingSnapshotContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_snapshot_signature,
      defaultStakingSnapshotContract.contractSignature
    )
    val defaultStakingStateContract = StakeState(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_state,
      defaultStakingStateContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_state_signature,
      defaultStakingStateContract.contractSignature
    )
    val defaultStakingVoteContract = StakeVote(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_vote,
      defaultStakingVoteContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_vote_signature,
      defaultStakingVoteContract.contractSignature
    )
    val defaultStakingUnstakeContract = Unstake(
      PaideiaContractSignature(version = "1.0.0", daoKey = dummyDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_unstake,
      defaultStakingUnstakeContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_staking_unstake_signature,
      defaultStakingUnstakeContract.contractSignature
    )

    paideiaConfig.set(
      ConfKeys.im_paideia_fees_compound_operator_paideia,
      Env.conf.getLong("im_paideia_fees_compound_operator_paideia")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_emit_paideia,
      Env.conf.getLong("im_paideia_fees_emit_paideia")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_emit_operator_paideia,
      Env.conf.getLong("im_paideia_fees_emit_operator_paideia")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_operator_max_erg,
      Env.conf.getLong("im_paideia_fees_operator_max_erg")
    )
    val stakeStakeContract = Stake(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_stake,
      stakeStakeContract.contractSignature
    )
    val stakeChangeContract = ChangeStake(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_changestake,
      stakeChangeContract.contractSignature
    )
    val stakeUnstakeContract = Unstake(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_unstake,
      stakeUnstakeContract.contractSignature
    )
    val stakeSnapshotContract = StakeSnapshot(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_snapshot,
      stakeSnapshotContract.contractSignature
    )
    val stakeVoteContract = StakeVote(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_vote,
      stakeVoteContract.contractSignature
    )
    val stakeCompoundContract = StakeCompound(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_compound,
      stakeCompoundContract.contractSignature
    )
    val stakeProfitshareContract = StakeProfitShare(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_profitshare,
      stakeProfitshareContract.contractSignature
    )
    val stakeStateContract = StakeState(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_state,
      stakeStateContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_governance_type,
      GovernanceType.DEFAULT.id.toByte
    )
    val proposalContract = ProposalBasic(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_proposal(proposalContract.ergoTree.bytes),
      proposalContract.contractSignature
    )
    val sendFundsContract = ActionSendFundsBasic(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_action(sendFundsContract.ergoTree.bytes),
      sendFundsContract.contractSignature
    )
    val updateConfigContract = ActionUpdateConfig(
      PaideiaContractSignature(version = "1.0.0", daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_action(updateConfigContract.ergoTree.bytes),
      updateConfigContract.contractSignature
    )
    val state =
      TotalStakingState(Env.paideiaDaoKey, Env.conf.getLong("emission_start"))
    Paideia._daoMap.remove(dummyDaoKey)
    Paideia._actorList.foreach((f: (String, PaideiaActor)) =>
      f._2.contractInstances
        .filter((p: (List[Byte], PaideiaContract)) =>
          p._2.contractSignature.daoKey == dummyDaoKey
        )
        .foreach((p: (List[Byte], PaideiaContract)) =>
          f._2.contractInstances.remove(p._1)
        )
    )
    Paideia._actorList.keys.foreach((s: String) =>
      logger.info(
        s"${s}: ${Paideia._actorList(s).contractInstances.size.toString()}"
      )
    )
  }
}
