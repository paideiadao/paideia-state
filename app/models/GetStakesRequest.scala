package models

import play.api.libs.json.Json

final case class GetStakesRequest(
    potentialKeys: List[String]
)

object GetStakesRequest {
  implicit val json = Json.format[GetStakesRequest]
}
