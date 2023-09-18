package models

import play.api.libs.json._

final case class UpdateConfigAction(
    optionId: Int,
    activationTime: Long,
    remove: Array[String],
    update: Array[DaoConfigValueEntry],
    insert: Array[DaoConfigValueEntry]
) extends Action

object UpdateConfigAction {
  implicit val format: Format[UpdateConfigAction] = Json.format
}
