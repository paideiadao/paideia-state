package models

import play.api.libs.json.Json

final case class MOutput(
    value: String,
    ergoTree: String,
    assets: Array[MToken],
    additionalRegisters: Map[String, String],
    creationHeight: Int
)

object MOutput {
  implicit val json = Json.format[MOutput]
}
