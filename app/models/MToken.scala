package models

import play.api.libs.json.Json

final case class MToken(
    tokenId: String,
    amount: String
)

object MToken {
  implicit val mTokenJson = Json.format[MToken]
}
