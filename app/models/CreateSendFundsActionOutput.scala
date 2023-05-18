package models

import play.api.libs.json.Json

final case class CreateSendFundsActionOutput(
    address: String,
    nergs: Long,
    tokens: List[(String, Long)],
    registers: List[String]
)

object CreateSendFundsActionOutput {
  implicit val json = Json.format[CreateSendFundsActionOutput]
}
