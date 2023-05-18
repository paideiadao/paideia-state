package models

import im.paideia.staking.StakeRecord
import play.api.libs.json.Json

final case class UnstakeRequest(
    daoKey: String,
    stakeKey: String,
    newStakeRecord: StakeRecord,
    userAddress: String,
    userAddresses: Array[String]
)

object UnstakeRequest {
  implicit val stakeRecordJson = Json.format[StakeRecord]
  implicit val json = Json.format[UnstakeRequest]
}
