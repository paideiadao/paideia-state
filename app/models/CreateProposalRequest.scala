package models

import play.api.libs.json.Json

final case class CreateProposalRequest(
    daoKey: String,
    voteKey: String,
    userAddress: String,
    userAddresses: Array[String],
    endTime: Long,
    sendFundsActions: Array[CreateSendFundsActionRequest],
    updateConfigActions: Array[CreateUpdateConfigActionRequest]
)

object CreateProposalRequest {
  implicit val json = Json.format[CreateProposalRequest]
}
