package models

import play.api.libs.json._

final case class SendFundsAction(
    activationTime: Long,
    optionId: Int,
    outputs: List[CreateSendFundsActionOutput]
) extends Action

object SendFundsAction {
  implicit val format: Format[SendFundsAction] = Json.format
}
