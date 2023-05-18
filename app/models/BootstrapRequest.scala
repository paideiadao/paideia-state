package models

import play.api.libs.json.Json

final case class BootstrapRequest(
    stakepoolSize: Long,
    userAddresses: Array[String]
)

object BootstrapRequest {
  implicit val json = Json.format[BootstrapRequest]
}
