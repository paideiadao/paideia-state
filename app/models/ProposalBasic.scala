package models

import play.api.libs.json._

final case class ProposalBasic(
    proposalIndex: Int,
    name: String,
    endTime: Long,
    passed: Int,
    actions: List[Action],
    votes: List[Long],
    box_height: Long,
    individual_votes: List[ProposalVote]
) extends Proposal

object ProposalBasic {
  implicit val format: Format[ProposalBasic] = Json.format
}
