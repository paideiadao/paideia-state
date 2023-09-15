package models

import play.api.libs.json.Json

final case class DaoConfigValueEntry(
    key: String,
    valueType: String,
    value: String
)

object DaoConfigValueEntry {
  implicit val json = Json.format[DaoConfigValueEntry]
}
