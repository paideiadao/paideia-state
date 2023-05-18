package models

import play.api.libs.json.Json

final case class CreateSendFundsActionRequest(
    optionId: Int,
    repeats: Int,
    repeatDelay: Long,
    activationTime: Long,
    outputs: Array[CreateSendFundsActionOutput]
)

object CreateSendFundsActionRequest {
  implicit val json = Json.format[CreateSendFundsActionRequest]
}
