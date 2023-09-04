package models

import play.api.libs.json.Json

final case class DaoConfigValueEntry(
    valueType: String,
    value: String
)

object DaoConfigValueEntry {
  implicit val json = Json.format[DaoConfigValueEntry]
}
