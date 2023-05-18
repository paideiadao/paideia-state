package models

import play.api.libs.json.Json

final case class CreateVoteRequest(
    daoKey: String,
    stakeKey: String,
    userAddress: String,
    userAddresses: Array[String]
)

object CreateVoteRequest {
  implicit val json = Json.format[CreateVoteRequest]
}
