package util

import org.ergoplatform.appkit.UnsignedTransaction
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.ErgoId
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.impl.UnsignedTransactionBuilderImpl
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.Parameters
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.OutBox
import org.ergoplatform.ErgoScriptPredef
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.wallet.protocol.context.ErgoLikeStateContext
import sigmastate.eval.Colls
import org.ergoplatform.appkit.impl.ScalaBridge
import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.UnsignedErgoLikeTransaction
import special.collection.Coll
import scorex.crypto.authds.ADDigest
import special.sigma.Header
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.ExtendedInputBox
import org.ergoplatform.DataInput
import scorex.crypto.authds.ADKey
import org.ergoplatform.ErgoBoxCandidate
import im.paideia.staking.transactions.StakeTransaction
import akka.actor.Address
import org.ergoplatform.appkit
import im.paideia.util.Env

object ChangeBoxFixer {
  def apply(
      _ctx: BlockchainContext,
      unsigned: UnsignedTransaction
  ): UnsignedTransaction = {
    val ctx = _ctx.asInstanceOf[BlockchainContextImpl]
    val rewardDelay =
      if (ctx.getNetworkType == NetworkType.MAINNET)
        Parameters.MinerRewardDelay_Mainnet
      else
        Parameters.MinerRewardDelay_Testnet
    val tokensInInput = HashMap[ErgoId, Long]()
    val tokensInOutput = HashMap[ErgoId, Long]()

    val fee = unsigned
      .getOutputs()
      .asScala
      .foldLeft(0L)((z: Long, b: OutBox) =>
        z + (if (
               b.getErgoTree()
                 .equals(ErgoScriptPredef.feeProposition(rewardDelay))
             ) b.getValue()
             else 0L)
      )

    unsigned
      .getInputs()
      .asScala
      .flatMap(_.getTokens().asScala)
      .foreach(et =>
        tokensInInput(et.getId()) =
          tokensInInput.getOrElse(et.getId(), 0L) + et.getValue()
      )

    unsigned
      .getOutputs()
      .asScala
      .flatMap(_.getTokens().asScala)
      .foreach(et =>
        tokensInOutput(et.getId()) =
          tokensInOutput.getOrElse(et.getId(), 0L) + et.getValue()
      )
    val originalBox =
      unsigned.getOutputs().get(unsigned.getOutputs().size() - 1)
    val changeBox =
      if (
        originalBox
          .getErgoTree()
          .equals(ErgoScriptPredef.feeProposition(rewardDelay))
      ) {
        List[OutBox]()
      } else {
        val outBoxBuilder = ctx
          .newTxBuilder()
          .outBoxBuilder()
          .contract(
            new ErgoTreeContract(
              originalBox.getErgoTree(),
              ctx.getNetworkType()
            )
          )
          .value(originalBox.getValue())
        val newTokens = originalBox
          .getTokens()
          .asScala
          .map(et =>
            if (!tokensInInput.contains(et.getId())) et
            else
              new ErgoToken(
                et.getId(),
                et.getValue() - (tokensInOutput(et.getId()) - tokensInInput(
                  et.getId()
                ))
              )
          )
          .filter(_.getValue() > 0L)
          .toList
        List(
          if (newTokens.size > 0)
            outBoxBuilder
              .tokens(
                newTokens: _*
              )
              .build()
          else
            outBoxBuilder.build()
        )
      }

    val ergoContext = createErgoLikeStateContext(ctx)

    new UnsignedTransactionImpl(
      new UnsignedErgoLikeTransaction(
        JavaHelpers
          .toIndexedSeq(unsigned.getInputs())
          .map(b =>
            ExtendedInputBox(
              b.asInstanceOf[InputBoxImpl].getErgoBox(),
              b.asInstanceOf[InputBoxImpl].getExtension()
            ).toUnsignedInput
          ),
        JavaHelpers
          .toIndexedSeq(unsigned.getDataInputs())
          .map(b => DataInput(ADKey @@ b.getId().getBytes())),
        JavaHelpers
          .toIndexedSeq(
            (unsigned
              .getOutputs()
              .subList(
                0,
                unsigned.getOutputs().size - changeBox.size
              )
              .asScala ++ changeBox).asJava
          )
          .map(b =>
            JavaHelpers.createBoxCandidate(
              b.getValue(),
              b.getErgoTree(),
              b.getTokens().asScala,
              b.getRegisters().asScala,
              b.getCreationHeight()
            )
          )
      ),
      unsigned
        .getInputs()
        .asScala
        .map(b =>
          ExtendedInputBox(
            b.asInstanceOf[InputBoxImpl].getErgoBox(),
            b.asInstanceOf[InputBoxImpl].getExtension()
          )
        )
        .asJava,
      unsigned
        .getDataInputs()
        .asScala
        .map(b => b.asInstanceOf[InputBoxImpl].getErgoBox())
        .asJava,
      if (changeBox.size > 0)
        new ErgoTreeContract(originalBox.getErgoTree(), ctx.getNetworkType())
          .toAddress()
          .getErgoAddress()
      else
        new ErgoTreeContract(
          unsigned.getInputs().get(0).getErgoTree(),
          ctx.getNetworkType()
        ).toAddress().getErgoAddress(),
      ergoContext,
      ctx,
      List[ErgoToken]().asJava
    )
  }

  def fixStuckProxy(tx: StakeTransaction): UnsignedTransaction = {

    val rewardDelay =
      if (tx._ctx.getNetworkType == NetworkType.MAINNET)
        Parameters.MinerRewardDelay_Mainnet
      else
        Parameters.MinerRewardDelay_Testnet
    val tokensInInput = HashMap[ErgoId, Long]()
    val tokensInOutput = HashMap[ErgoId, Long]()

    val fee = 1000000L

    tx.outputs = tx.outputs ++ List(
      tx._ctx
        .newTxBuilder()
        .outBoxBuilder()
        .contract(
          new ErgoTreeContract(
            ErgoScriptPredef.feeProposition(rewardDelay),
            tx._ctx.getNetworkType()
          )
        )
        .value(1000000L)
        .build(),
      tx._ctx
        .newTxBuilder()
        .outBoxBuilder()
        .contract(appkit.Address.create(Env.operatorAddress).toErgoContract())
        .value(1000000L)
        .tokens(
          new ErgoToken(Env.paideiaTokenId, Env.conf.getLong("defaultBotFee"))
        )
        .build()
    )

    tx.inputs
      .flatMap(_.getTokens().asScala)
      .foreach(et =>
        tokensInInput(et.getId()) =
          tokensInInput.getOrElse(et.getId(), 0L) + et.getValue()
      )

    tx.outputs
      .flatMap(_.getTokens().asScala)
      .foreach(et =>
        tokensInOutput(et.getId()) =
          tokensInOutput.getOrElse(et.getId(), 0L) + et.getValue()
      )

    val ergoContext = createErgoLikeStateContext(tx._ctx)

    new UnsignedTransactionImpl(
      new UnsignedErgoLikeTransaction(
        JavaHelpers
          .toIndexedSeq(tx.inputs.asJava)
          .map(b =>
            ExtendedInputBox(
              b.asInstanceOf[InputBoxImpl].getErgoBox(),
              b.asInstanceOf[InputBoxImpl].getExtension()
            ).toUnsignedInput
          ),
        JavaHelpers
          .toIndexedSeq(tx.dataInputs.asJava)
          .map(b => DataInput(ADKey @@ b.getId().getBytes())),
        JavaHelpers
          .toIndexedSeq(
            tx.outputs.asJava
          )
          .map(b =>
            JavaHelpers.createBoxCandidate(
              b.getValue(),
              b.getErgoTree(),
              b.getTokens().asScala,
              b.getRegisters().asScala,
              b.getCreationHeight()
            )
          )
      ),
      tx.inputs
        .map(b =>
          ExtendedInputBox(
            b.asInstanceOf[InputBoxImpl].getErgoBox(),
            b.asInstanceOf[InputBoxImpl].getExtension()
          )
        )
        .asJava,
      tx.dataInputs
        .map(b => b.asInstanceOf[InputBoxImpl].getErgoBox())
        .asJava,
      appkit.Address.create(Env.operatorAddress).getErgoAddress(),
      ergoContext,
      tx._ctx,
      List[ErgoToken]().asJava
    )
  }

  private def createErgoLikeStateContext(
      _ctx: BlockchainContextImpl
  ): ErgoLikeStateContext = new ErgoLikeStateContext() {
    private val _allHeaders = Colls.fromArray(
      _ctx.getHeaders.iterator.asScala
        .map(h => ScalaBridge.toSigmaHeader(h))
        .toArray
    )

    private val _headers = _allHeaders.slice(1, _allHeaders.length)

    private val _preHeader = JavaHelpers.toPreHeader(_allHeaders.apply(0))

    override def sigmaLastHeaders: Coll[Header] = _headers

    override def previousStateDigest: ADDigest =
      ADDigest @@ JavaHelpers.getStateDigest(_headers.apply(0).stateRoot)

    override def sigmaPreHeader: special.sigma.PreHeader = _preHeader
  }
}
