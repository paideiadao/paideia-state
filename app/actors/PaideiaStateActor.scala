package actors

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
import special.sigma.Box
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.ErgoValue
import sigmastate.eval.CostingBox
import org.ergoplatform.appkit.impl.InputBoxImpl
import im.paideia.util.Util
import java.nio.charset.StandardCharsets
import scala.collection.mutable.HashMap
import im.paideia.DAOConfigKey
import _root_.im.paideia.DAOConfigValue
import im.paideia.common.filtering.FilterLeaf
import im.paideia.common.filtering.FilterType
import im.paideia.common.filtering.CompareField
import scorex.crypto.authds.ADDigest
import special.sigma.AvlTree
import sigmastate.eval.Colls
import im.paideia.common.filtering.FilterNode
import im.paideia.governance.VoteRecord
import im.paideia.staking.boxes.StakeStateBox
import im.paideia.DAOConfigValueSerializer
import play.api.libs.json.Json
import im.paideia.staking.ParticipationRecord
import special.collection.Coll
import org.ergoplatform.appkit.ErgoContract
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.InputBox
import im.paideia.governance.boxes.ProposalBasicBox
import models.Proposal
import scorex.crypto.hash.Blake2b256
import im.paideia.governance.boxes.ActionSendFundsBasicBox
import models.CreateSendFundsActionOutput
import scala.reflect.io.File
import im.paideia.DAOConfigValueDeserializer
import models.DaoConfigValueEntry

object PaideiaStateActor {
  def props = Props[PaideiaStateActor]

  case class CreateDAOBox(
      ctx: BlockchainContextImpl,
      daoName: String,
      daoGovernanceTokenId: String,
      stakePoolSize: Long,
      governanceType: GovernanceType.Value,
      quorum: Byte,
      threshold: Byte,
      stakingEmissionAmount: Long,
      stakingEmissionDelay: Byte,
      stakingCycleLength: Long,
      stakingProfitSharePct: Byte,
      userAddress: String,
      pureParticipationWeight: Byte,
      participationWeight: Byte
  )

  case class StakeBox(
      ctx: BlockchainContextImpl,
      daoKey: String,
      userAddress: String,
      stakeAmount: Long
  )

  case class AddStakeBox(
      ctx: BlockchainContextImpl,
      daoKey: String,
      stakeKey: String,
      userAddress: String,
      addStakeAmount: Long
  )

  case class UnstakeBox(
      ctx: BlockchainContextImpl,
      daoKey: String,
      stakeKey: String,
      userAddress: String,
      newStakeRecord: StakeRecord
  )

  case class BlockchainEvent(
      event: PaideiaEvent
  )

  case class GetStake(
      daoKey: String,
      stakeKey: String
  )

  case class GetDaoStake(
      ctx: BlockchainContextImpl,
      daoKey: String
  )

  case class Bootstrap(
      ctx: BlockchainContextImpl,
      stakepoolSize: Long
  )

  trait ProposalAction

  case class CreateProposalBox(
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

  case class GetAllDAOs()

  case class GetDAOConfig(
      daoKey: String
  )

  case class GetDAOTreasury(
      daoKey: String
  )

  case class CastVoteBox(
      ctx: BlockchainContextImpl,
      daoKey: String,
      stakeKey: String,
      proposalIndex: Int,
      votes: Array[Long],
      userAddress: String
  )

  case class StakeInfo(
      stakeRecord: StakeRecord,
      participationRecord: Option[ParticipationRecord],
      profitTokens: List[String]
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
      profitTokens: List[String]
  )

  object DaoStakeInfo {
    implicit val json = Json.format[DaoStakeInfo]
  }

  case class GetContractSignature(
      contractHash: Option[List[Byte]],
      contractAddress: Option[String]
  )

  case class GetDAOProposals(daoKey: String)

  case class GetDAOProposal(
      ctx: BlockchainContextImpl,
      daoKey: String,
      proposalIndex: Int
  )
}

class PaideiaStateActor extends Actor with Logging {
  import PaideiaStateActor._

  initiate

  def receive = {
    case c: CreateDAOBox         => sender() ! createDAOBox(c)
    case s: StakeBox             => sender() ! stakeBox(s)
    case a: AddStakeBox          => sender() ! addStakeBox(a)
    case u: UnstakeBox           => sender() ! unstakeBox(u)
    case g: GetStake             => sender() ! getStake(g)
    case g: GetDaoStake          => sender() ! getDaoStake(g)
    case e: BlockchainEvent      => sender() ! handleEvent(e)
    case p: CreateProposalBox    => sender() ! createProposal(p)
    case b: Bootstrap            => sender() ! bootstrap(b)
    case g: GetAllDAOs           => sender() ! getAllDAOs(g)
    case g: GetDAOConfig         => sender() ! getDAOConfig(g)
    case g: GetDAOTreasury       => sender() ! getDAOTreasury(g)
    case c: CastVoteBox          => sender() ! castVoteBox(c)
    case g: GetContractSignature => sender() ! getContractSignature(g)
    case g: GetDAOProposals      => sender() ! getDAOProposals(g)
    case g: GetDAOProposal       => sender() ! getDAOProposal(g)
  }

  def getDAOProposal(g: GetDAOProposal): Try[Proposal] =
    Try {
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
              val ac = models.SendFundsAction(
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
              logger.info(ac.toString())
              ac
            case _ => throw new Exception("Unknown action contract")
          }
        })

      val proposalContract = Paideia.getProposalContract(
        Blake2b256(proposalBox.getErgoTree().bytes).array.toList
      )
      proposalContract match {
        case pb: ProposalBasic =>
          val pbBox = ProposalBasicBox.fromInputBox(g.ctx, proposalBox)
          models.ProposalBasic(
            pbBox.proposalIndex,
            pbBox.name,
            pbBox.endTime,
            pbBox.passed,
            actions,
            pbBox.voteCount.toList,
            proposalBox.getCreationHeight()
          )
        case _ => throw new Exception("Unknown proposal type")
      }
    }

  def getDAOProposals(g: GetDAOProposals): Try[List[(Int, String, Int)]] =
    Try {
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
          logger.info(p.name)
          logger.info(p.proposalIndex.toString())
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
            pBox.map(_.getCreationHeight()).getOrElse(0)
          )
        })
        .toList
        .filter(p => p._3 > 0)
    }

  def getContractSignature(
      g: GetContractSignature
  ): Try[PaideiaContractSignature] =
    Try {
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
        .map(_._2.contractSignature)
        .getOrElse(PaideiaContractSignature(className = "Unknown"))
    }

  def castVoteBox(c: CastVoteBox): Try[OutBox] =
    Try {
      CastVote(PaideiaContractSignature(daoKey = c.daoKey))
        .box(
          c.ctx,
          c.stakeKey,
          c.proposalIndex,
          VoteRecord(c.votes),
          Address.create(c.userAddress)
        )
        .outBox
    }

  def getDAOTreasury(g: GetDAOTreasury): Try[String] =
    Try {
      Treasury(PaideiaContractSignature(daoKey = g.daoKey)).contract
        .toAddress()
        .toString()
    }

  def getDAOConfig(g: GetDAOConfig): Try[Map[String, Array[Byte]]] =
    Try {
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
            properKnownKeys(cv._1.hashedKey.toList).getOrElse("Unknown key"),
            cv._2
          )
        )
    }

  def getAllDAOs(g: GetAllDAOs): Try[HashMap[String, (String, Int)]] = Try {
    Paideia._daoMap.map(d => {
      val configContract = Config(
        d._2
          .config[PaideiaContractSignature](
            ConfKeys.im_paideia_contracts_config
          )
          .withDaoKey(d._2.key)
      )
      val configBoxUpdateHeight =
        configContract
          .boxes(configContract.getUtxoSet.toList(0))
          .getCreationHeight()
      (
        d._1,
        (
          d._2.config[String](ConfKeys.im_paideia_dao_name),
          configBoxUpdateHeight
        )
      )
    })
  }

  def getStake(g: GetStake): Try[StakeInfo] =
    Try {
      val key = ErgoId.create(g.stakeKey)
      val profitTokenIds = Paideia
        .getConfig(g.daoKey)
        .getArray[Object](ConfKeys.im_paideia_staking_profit_tokenids)
        .map(o => new ErgoId(o.asInstanceOf[Coll[Byte]].toArray).toString())
      StakeInfo(
        TotalStakingState(g.daoKey).currentStakingState.stakeRecords
          .getMap(None)
          .get
          .toMap(key),
        TotalStakingState(g.daoKey).currentStakingState.participationRecords
          .getMap(None)
          .get
          .toMap
          .get(key),
        profitTokenIds.toList
      )
    }

  def getDaoStake(g: GetDaoStake): Try[DaoStakeInfo] =
    Try {
      val profitTokenIds = Paideia
        .getConfig(g.daoKey)
        .getArray[Object](ConfKeys.im_paideia_staking_profit_tokenids)
        .map(o => new ErgoId(o.asInstanceOf[Coll[Byte]].toArray).toString())
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
        TotalStakingState(g.daoKey).currentStakingState.totalStaked(),
        TotalStakingState(g.daoKey).currentStakingState.stakers(),
        stakeStateBox.profit,
        stakeStateBox.voted,
        stakeStateBox.votedTotal,
        stakeStateBox.nextEmission,
        profitTokenIds.toList
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

    CostingBox(
      boxBuilder
        .build()
        .convertToInputWith(Util.randomKey, 0.toShort)
        .asInstanceOf[InputBoxImpl]
        .getErgoBox()
    )
  }

  def createProposal(c: CreateProposalBox): Try[OutBox] = Try {
    val proposalIndex = Long.MaxValue - Paideia
      .getBox(
        new FilterNode(
          FilterType.FTALL,
          List(
            new FilterLeaf(
              FilterType.FTEQ,
              Env.daoTokenId,
              CompareField.ASSET,
              0
            ),
            new FilterLeaf(
              FilterType.FTEQ,
              ErgoId.create(c.daoKey).getBytes.toIterable,
              CompareField.REGISTER,
              0
            )
          )
        )
      )(0)
      .getTokens
      .get(1)
      .getValue
    val proposal =
      Paideia.getDAO(c.daoKey).newProposal(proposalIndex.toInt, c.name)
    val actions = c.actions.map(a =>
      a match {
        case s: SendFundsAction =>
          ActionSendFundsBasic(PaideiaContractSignature(daoKey = c.daoKey))
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
                .getMap(None)
                .get
                .toMap
                .contains(DAOConfigKey(k))
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
                .getMap(None)
                .get
                .toMap
                .contains(DAOConfigKey(dcv.key))
            ) {
              val properKnownKeys =
                DAOConfigKey.knownKeys.map(kv => (kv._1.toList, kv._2))
              throw new Exception(
                "DAO Config %s does not contain the key '%s'".format(
                  Paideia
                    .getConfig(c.daoKey)
                    ._config
                    .getMap(None)
                    .get
                    .toMap
                    .map(e => properKnownKeys(e._1.hashedKey.toList).getOrElse("Unknown key")),
                  dcv.key
                )
              )
            if (
              !(DAOConfigValueDeserializer
                .getType(
                  Paideia
                    .getConfig(c.daoKey)
                    ._config
                    .getMap(None)
                    .get
                    .toMap
                    .get(DAOConfigKey(dcv.key))
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
                .getMap(None)
                .get
                .toMap
                .contains(DAOConfigKey(dcv.key))
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
          ActionUpdateConfig(PaideiaContractSignature(daoKey = c.daoKey))
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
      ProposalBasic(PaideiaContractSignature(daoKey = c.daoKey))
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
    logger.info(proposalBox.toString())
    val box = CreateProposal(PaideiaContractSignature(daoKey = c.daoKey))
      .box(
        c.ctx,
        c.name,
        proposalBox,
        actions,
        c.voteKey,
        Address.create(c.userAddress)
      )

    val serialized = box.registers(1).toHex()
    logger.info(serialized)
    val deserialized = ErgoValue.fromHex(serialized)
    logger.info(deserialized.toString())

    box.outBox
  }

  def createDAOBox(c: CreateDAOBox): Try[OutBox] = Try {
    ProtoDAOProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).box(
      c.ctx,
      Paideia.getConfig(Env.paideiaDaoKey),
      c.daoName,
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
      c.participationWeight
    ).outBox
  }

  def stakeBox(s: StakeBox): Try[OutBox] = try {
    Success(
      StakeProxy(
        PaideiaContractSignature(daoKey = s.daoKey)
      ).box(s.ctx, s.userAddress, s.stakeAmount).outBox
    )
  } catch {
    case e: Exception => Failure(e)
  }

  def addStakeBox(a: AddStakeBox): OutBox = AddStakeProxy(
    PaideiaContractSignature(daoKey = a.daoKey)
  ).box(a.ctx, a.stakeKey, a.addStakeAmount, a.userAddress).outBox

  def unstakeBox(u: UnstakeBox): OutBox =
    UnstakeProxy(PaideiaContractSignature(daoKey = u.daoKey))
      .box(u.ctx, u.stakeKey, u.newStakeRecord, u.userAddress)
      .outBox

  def handleEvent(e: BlockchainEvent): Try[PaideiaEventResponse] =
    try {
      Success(Paideia.handleEvent(e.event))
    } catch {
      case exception: Exception =>
        Failure(exception)
    }

  def bootstrap(b: Bootstrap): Array[OutBox] = {
    val stakeBox =
      StakeState(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .emptyBox(b.ctx, Paideia.getDAO(Env.paideiaDaoKey), b.stakepoolSize)
    stakeBox.value = 2000000L
    Array(
      PaideiaOrigin(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx, Paideia.getConfig(Env.paideiaDaoKey), Long.MaxValue - 1L)
        .outBox,
      DAOOrigin(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(
          b.ctx,
          Paideia.getDAO(Env.paideiaDaoKey),
          Long.MaxValue,
          Long.MaxValue
        )
        .outBox,
      Config(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(
          b.ctx,
          Paideia.getDAO(Env.paideiaDaoKey),
          Some(Paideia.getConfig(Env.paideiaDaoKey)._config.digest)
        )
        .outBox,
      ChangeStake(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      Stake(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      Unstake(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      StakeSnapshot(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      StakeCompound(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      StakeVote(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      StakeProfitShare(PaideiaContractSignature(daoKey = Env.paideiaDaoKey))
        .box(b.ctx)
        .outBox,
      stakeBox.outBox
    )
  }

  def initiate = {
    val daoconfigdir = File("./daoconfigs/").toAbsolute.toDirectory

    if (!daoconfigdir.exists)
      daoconfigdir.createDirectory()
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
      ConfKeys.im_paideia_staking_profit_thresholds,
      Array(1000L, 1000L)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_staking_profit_tokenids,
      Array[Array[Byte]]()
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
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val paideiaOriginContract = PaideiaOrigin(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val protoDaoProxyContract = ProtoDAOProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val treasuryContract = Treasury(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val protoDAOContract = ProtoDAO(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val mintContract = Mint(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val daoContract = DAOOrigin(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    val splitProfitContract = SplitProfit(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_treasury,
      treasuryContract.contractSignature
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = dummyDaoKey)
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
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_stake,
      stakeStakeContract.contractSignature
    )
    val stakeChangeContract = ChangeStake(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_changestake,
      stakeChangeContract.contractSignature
    )
    val stakeUnstakeContract = Unstake(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_unstake,
      stakeUnstakeContract.contractSignature
    )
    val stakeSnapshotContract = StakeSnapshot(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_snapshot,
      stakeSnapshotContract.contractSignature
    )
    val stakeVoteContract = StakeVote(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_vote,
      stakeVoteContract.contractSignature
    )
    val stakeCompoundContract = StakeCompound(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_compound,
      stakeCompoundContract.contractSignature
    )
    val stakeProfitshareContract = StakeProfitShare(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_profitshare,
      stakeProfitshareContract.contractSignature
    )
    val stakeStateContract = StakeState(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
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
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_proposal(proposalContract.ergoTree.bytes),
      proposalContract.contractSignature
    )
    val sendFundsContract = ActionSendFundsBasic(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_action(sendFundsContract.ergoTree.bytes),
      sendFundsContract.contractSignature
    )
    val updateConfigContract = ActionUpdateConfig(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_action(updateConfigContract.ergoTree.bytes),
      updateConfigContract.contractSignature
    )
    val state =
      TotalStakingState(Env.paideiaDaoKey, Env.conf.getLong("emission_start"))
    val stakeProxy = StakeProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    val addStakeProxy = AddStakeProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    val unstakeProxy = UnstakeProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    val createProposal = CreateProposal(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    val castVote = CastVote(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
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
