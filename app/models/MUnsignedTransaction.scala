package models

import org.ergoplatform.appkit.UnsignedTransaction
import play.api.libs.json.Json
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.ExtendedInputBox
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.ErgoValue
import scorex.util.encode.Base16
import sigma.serialization.ValueSerializer
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import im.paideia.common.transactions.PaideiaTransaction
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import scorex.util.ModifierId
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.sdk.ErgoId
import im.paideia.util.Env
import org.ergoplatform.ErgoAddress
import org.ergoplatform.appkit.OutBox

final case class MUnsignedTransaction(
    inputs: Array[MInput],
    dataInputs: Array[MInput],
    outputs: Array[MOutput]
)

object MUnsignedTransaction {

  implicit val munsignedTransactionJson = Json.format[MUnsignedTransaction]

  def uiFeeBox(ctx: BlockchainContextImpl, uiFee: Long): OutBox = {
    ctx
      .newTxBuilder()
      .outBoxBuilder()
      .contract(
        Address.create(Env.conf.getString("uiFeeAddress")).toErgoContract()
      )
      .value(uiFee)
      .build()
  }

  def apply(
      paideiaTransaction: PaideiaTransaction,
      userAddresses: Array[String],
      uiFee: Long
  ): MUnsignedTransaction = {
    if (uiFee > 0L) {
      paideiaTransaction.outputs = paideiaTransaction.outputs ++ List(
        uiFeeBox(paideiaTransaction.ctx, uiFee)
      )
    }
    apply(paideiaTransaction, userAddresses)
  }

  def apply(
      paideiaTransaction: PaideiaTransaction,
      userAddresses: Array[String]
  ): MUnsignedTransaction = {
    val userFundsNeeded = paideiaTransaction.fundsMissing()
    paideiaTransaction.minimizeChangeBox = false
    paideiaTransaction.userInputs = BoxOperations
      .createForSenders(
        userAddresses
          .map(addr => Address.create(addr))
          .toList
          .asJava,
        paideiaTransaction.ctx
      )
      .withInputBoxesLoader(
        new ExplorerAndPoolUnspentBoxesLoader()
          .withAllowChainedTx(true)
      )
      .withAmountToSpend(userFundsNeeded._1)
      .withTokensToSpend(
        userFundsNeeded._2
          .filter((t: (ModifierId, Long)) => t._2 > 0L)
          .map((t: (ModifierId, Long)) =>
            ErgoToken(new ErgoId(t._1.toBytes), t._2)
          )
          .toList
          .asJava
      )
      .loadTop()
      .asScala
      .toList

    MUnsignedTransaction(paideiaTransaction.unsigned())
  }

  def apply(unsigned: UnsignedTransaction): MUnsignedTransaction = {
    val inputs = unsigned
      .getInputs()
      .asScala
      .zip(
        unsigned
          .asInstanceOf[UnsignedTransactionImpl]
          .getBoxesToSpend()
          .asScala
      )
      .map(inp =>
        MInput(
          inp._2.extension,
          inp._1.getId().toString(),
          inp._1.getValue().toString(),
          inp._1.getErgoTree().bytesHex,
          inp._1
            .getTokens()
            .asScala
            .map(token =>
              MToken(token.getId.toString(), token.getValue.toString())
            )
            .toArray,
          inp._1
            .getRegisters()
            .asScala
            .zipWithIndex
            .map(kv => ("R" + (kv._2 + 4).toString(), kv._1.toHex()))
            .toMap,
          inp._1.getCreationHeight(),
          inp._1
            .asInstanceOf[InputBoxImpl]
            .getErgoBox()
            .transactionId
            .toString(),
          inp._1.asInstanceOf[InputBoxImpl].getErgoBox().index
        )
      )
      .toArray
      .asInstanceOf[Array[MInput]]
    val dataInputs = unsigned
      .getDataInputs()
      .asScala
      .map(inp =>
        MInput(
          inp
            .asInstanceOf[InputBoxImpl]
            .getExtension(),
          inp.getId().toString(),
          inp.getValue().toString(),
          inp.getErgoTree().bytesHex,
          inp
            .getTokens()
            .asScala
            .map(token =>
              MToken(token.getId.toString(), token.getValue.toString())
            )
            .toArray,
          inp
            .getRegisters()
            .asScala
            .zipWithIndex
            .map(kv => ("R" + (kv._2 + 4).toString(), kv._1.toHex()))
            .toMap,
          inp.getCreationHeight(),
          inp.asInstanceOf[InputBoxImpl].getErgoBox().transactionId.toString(),
          inp.asInstanceOf[InputBoxImpl].getErgoBox().index
        )
      )
      .toArray
      .asInstanceOf[Array[MInput]]
    val outputs = unsigned
      .getOutputs()
      .asScala
      .map(outp =>
        MOutput(
          outp.getValue().toString(),
          outp.getErgoTree().bytesHex,
          outp
            .getTokens()
            .asScala
            .map(token =>
              MToken(token.getId.toString(), token.getValue.toString())
            )
            .toArray,
          outp
            .getRegisters()
            .asScala
            .zipWithIndex
            .map(kv => ("R" + (kv._2 + 4).toString(), kv._1.toHex()))
            .toMap,
          outp.getCreationHeight()
        )
      )
      .toArray
      .asInstanceOf[Array[MOutput]]
    MUnsignedTransaction(inputs, dataInputs, outputs)
  }
}
