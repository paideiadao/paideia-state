package models

import play.api.libs.json.Json

final case class ProposalVote(
    stakeKey: String,
    vote: List[Long]
)

final case class ProposalBase(
    proposalIndex: Int,
    proposalName: String,
    proposalHeight: Int
)

object ProposalBase {
  implicit val json = Json.format[ProposalBase]
}

object ProposalVote {
  implicit val json = Json.format[ProposalVote]
}
