package models

import play.api.libs.json.Json
import actors.PaideiaStateActor

final case class CreateUpdateConfigActionRequest(
    optionId: Int,
    activationTime: Long,
    remove: Array[String],
    update: Array[(String, PaideiaStateActor.CValue)],
    insert: Array[(String, PaideiaStateActor.CValue)]
)

object CreateUpdateConfigActionRequest {
  implicit val json = Json.format[CreateUpdateConfigActionRequest]
}
