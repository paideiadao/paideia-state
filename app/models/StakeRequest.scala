package models

import play.api.libs.json.Json

final case class StakeRequest(
    daoKey: String,
    userAddress: String,
    stakeAmount: Long,
    userAddresses: Array[String]
)

object StakeRequest {
  implicit val json = Json.format[StakeRequest]
}
