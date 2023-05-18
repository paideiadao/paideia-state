package models

import play.api.libs.json.Json

final case class AddStakeRequest(
    daoKey: String,
    stakeKey: String,
    userAddress: String,
    addStakeAmount: Long,
    userAddresses: Array[String]
)

object AddStakeRequest {
  implicit val json = Json.format[AddStakeRequest]
}
