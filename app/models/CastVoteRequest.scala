package models

import play.api.libs.json.Json

final case class CastVoteRequest(
    daoKey: String,
    stakeKey: String,
    proposalIndex: Int,
    votes: Array[Long],
    userAddress: String,
    userAddresses: Array[String]
)

object CastVoteRequest {
  implicit val json = Json.format[CastVoteRequest]
}
