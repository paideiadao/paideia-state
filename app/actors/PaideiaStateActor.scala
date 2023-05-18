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
import org.ergoplatform.appkit.ErgoId
import im.paideia.common.contracts._
import im.paideia.governance.contracts._
import im.paideia.staking.contracts._
import im.paideia.staking.TotalStakingState
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import play.api.Logging
import special.sigma.Box
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.ErgoValue
import sigmastate.eval.CostingBox
import org.ergoplatform.appkit.impl.InputBoxImpl
import im.paideia.util.Util
import java.nio.charset.StandardCharsets
import scala.collection.mutable.HashMap
import im.paideia.DAOConfigKey
import _root_.im.paideia.DAOConfigValue

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

  case class Bootstrap(
      ctx: BlockchainContextImpl,
      stakepoolSize: Long
  )

  trait ProposalAction

  case class CreateProposalBox(
      ctx: BlockchainContextImpl,
      daoKey: String,
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

  case class GetAllDAOs()

  case class GetDAOConfig(
      daoKey: String
  )
}

class PaideiaStateActor extends Actor with Logging {
  import PaideiaStateActor._

  initiate

  def receive = {
    case c: CreateDAOBox      => sender() ! createDAOBox(c)
    case s: StakeBox          => sender() ! stakeBox(s)
    case a: AddStakeBox       => sender() ! addStakeBox(a)
    case u: UnstakeBox        => sender() ! unstakeBox(u)
    case g: GetStake          => sender() ! getStake(g)
    case e: BlockchainEvent   => sender() ! handleEvent(e)
    case p: CreateProposalBox => sender() ! createProposal(p)
    case b: Bootstrap         => sender() ! bootstrap(b)
    case g: GetAllDAOs        => sender() ! getAllDAOs(g)
    case g: GetDAOConfig      => sender() ! getDAOConfig(g)
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

  def getAllDAOs(g: GetAllDAOs): Try[HashMap[String, String]] = Try {
    Paideia._daoMap.map(d =>
      (
        d._1,
        d._2.config[String](ConfKeys.im_paideia_dao_name)
      )
    )
  }

  def getStake(g: GetStake): Try[StakeRecord] = Try {
    TotalStakingState(g.daoKey).currentStakingState.stakeRecords
      .getMap(None)
      .get
      .toMap(ErgoId.create(g.stakeKey))
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
      .value(Math.min(nergs, 1000000L))
    if (tokens.size > 0)
      boxBuilder.tokens(tokens: _*)
    if (registers.size > 0)
      boxBuilder.registers(registers: _*)

    CostingBox(
      false,
      boxBuilder
        .build()
        .convertToInputWith(Util.randomKey, 0.toShort)
        .asInstanceOf[InputBoxImpl]
        .getErgoBox()
    )
  }

  def createProposal(c: CreateProposalBox): Try[OutBox] = Try {
    val proposal = Paideia.getDAO(c.daoKey).newProposal
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
      }
    )
    val proposalBox =
      ProposalBasic(PaideiaContractSignature(daoKey = c.daoKey))
        .box(
          c.ctx,
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
      case e: Exception => Failure(e)
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
        .box(b.ctx, Paideia.getDAO(Env.paideiaDaoKey))
        .outBox,
      stakeBox.outBox
    )
  }

  def initiate = {
    val paideiaConfig = DAOConfig(Env.paideiaDaoKey)
    paideiaConfig.set(ConfKeys.im_paideia_dao_name, "Paideia")
    paideiaConfig.set(ConfKeys.im_paideia_dao_quorum, 15.toByte)
    paideiaConfig.set(ConfKeys.im_paideia_dao_threshold, 50)
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_tokenid,
      ErgoId
        .create(Env.paideiaTokenId)
        .getBytes()
    )
    paideiaConfig.set(ConfKeys.im_paideia_staking_cyclelength, 7200000L)
    paideiaConfig.set(ConfKeys.im_paideia_staking_emission_amount, 273970000L)
    paideiaConfig.set(ConfKeys.im_paideia_staking_emission_delay, 4L)
    paideiaConfig.set(ConfKeys.im_paideia_staking_profit_share_pct, 50.toByte)
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
        .getBytes()
    )

    paideiaConfig.set(
      ConfKeys.im_paideia_fees_createdao_erg,
      Env.conf.getLong("im_paideia_fees_createdao_erg")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_fees_createdao_paideia,
      Env.conf.getLong("im_paideia_fees_createdao_paideia")
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_key,
      ErgoId.create(Env.paideiaDaoKey).getBytes()
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_action_tokenid,
      ErgoId
        .create(Env.conf.getString("im_paideia_dao_action_tokenid"))
        .getBytes()
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_vote_tokenid,
      ErgoId
        .create(Env.conf.getString("im_paideia_dao_vote_tokenid"))
        .getBytes()
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_dao_proposal_tokenid,
      ErgoId
        .create(Env.conf.getString("im_paideia_dao_proposal_tokenid"))
        .getBytes()
    )
    Paideia.addDAO(DAO(Env.paideiaDaoKey, paideiaConfig))
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
    paideiaConfig.set(
      ConfKeys.im_paideia_default_treasury,
      treasuryContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_treasury_signature,
      treasuryContract.contractSignature
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_config,
      configContract.ergoTree.bytes
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_default_config_signature,
      configContract.contractSignature
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
    val plasmaContract = StakeState(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    )
    paideiaConfig.set(
      ConfKeys.im_paideia_contracts_staking_state,
      plasmaContract.contractSignature
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
    val state = TotalStakingState(Env.paideiaDaoKey, 1680098400000L)
    val stakeProxy = StakeProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    val addStakeProxy = AddStakeProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    val unstakeProxy = UnstakeProxy(
      PaideiaContractSignature(daoKey = Env.paideiaDaoKey)
    ).contractSignature
    Paideia._actorList.values.foreach(pa => {
      logger.info(s"""Actor: ${pa.getClass().getCanonicalName()}""")
      pa.contractInstances.values.foreach(pc =>
        logger.info(
          s"""Daokey: ${pc.contractSignature.daoKey} Contract address: ${pc.contract
              .toAddress()
              .toString()}"""
        )
      )
    })
    logger.info(DAOConfigKey.knownKeys.toString())
  }
}
