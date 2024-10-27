package models

import play.api.libs.json.Json
import play.api.libs.json.JsonConfiguration
import play.api.libs.json.OptionHandlers
import play.api.libs.json.JsonNaming
import sigma.interpreter.ContextExtension
import play.api.libs.json.Reads
import play.api.libs.json.Reads.mapReads
import play.api.libs.json.MapWrites.mapWrites
import play.api.libs.json.JsResult
import play.api.libs.json.Writes
import scala.util.Try
import play.api.libs.json.JsValue
import scorex.util.encode.Base16
import sigma.serialization.ValueSerializer
import play.api.libs.json.JsSuccess
import sigma.serialization.ConstantSerializer
import sigma.serialization.DataSerializer
import sigma.ast.EvaluatedValue
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.AppkitIso

final case class MInput(
    extension: ContextExtension,
    boxId: String,
    value: String,
    ergoTree: String,
    assets: Array[MToken],
    additionalRegisters: Map[String, String],
    creationHeight: Int,
    transactionId: String,
    index: Short
)

object MInput {
  implicit val reads: Reads[ContextExtension] =
    new Reads[ContextExtension] {
      def reads(js: JsValue): JsResult[ContextExtension] =
        JsSuccess(
          ContextExtension(js.as[Map[String, String]].map { case (k, v) =>
            k.toByte -> AppkitIso.isoErgoValueToSValue.to(ErgoValue.fromHex(v))
          })
        )
    }

  implicit val writes: Writes[ContextExtension] =
    new Writes[ContextExtension] {
      def writes(ce: ContextExtension): JsValue =
        Json.obj(ce.values.toSeq.map { case (s, o) =>
          val ret: (String, Json.JsValueWrapper) =
            s.toString -> Base16.encode(ValueSerializer.serialize(o))
          ret
        }: _*)
    }
  implicit val json = Json.format[MInput]
}
