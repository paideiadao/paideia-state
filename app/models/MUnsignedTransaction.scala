package models

import org.ergoplatform.appkit.UnsignedTransaction
import play.api.libs.json.Json
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.ExtendedInputBox
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.ErgoValue
import scorex.util.encode.Base16
import sigmastate.serialization.ValueSerializer
import sigmastate.Values
import sigmastate.SType

final case class MUnsignedTransaction(
    inputs: Array[MInput],
    dataInputs: Array[MInput],
    outputs: Array[MOutput]
)

object MUnsignedTransaction {

  implicit val munsignedTransactionJson = Json.format[MUnsignedTransaction]

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
          inp._2.extension.values.toMap
            .map(kv =>
              (
                kv._1.toString(),
                Base16.encode(ValueSerializer.serialize(kv._2))
              )
            ),
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
            .getExtension()
            .values
            .map(kv => (kv._1.toString(), kv._2.toString())),
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
