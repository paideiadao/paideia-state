package models

import play.api.libs.json.Json

final case class ProposalBase(
    proposalIndex: Int,
    proposalName: String,
    proposalHeight: Int
)

object ProposalBase {
  implicit val json = Json.format[ProposalBase]
}
