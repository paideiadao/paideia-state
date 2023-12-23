package models

import im.paideia.governance.GovernanceType
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

final case class CreateDAORequest(
    name: String,
    url: String,
    logo: String,
    description: String,
    minProposalTime: Long,
    banner: String,
    bannerEnabled: Boolean,
    footer: String,
    footerEnabled: Boolean,
    theme: String,
    daoGovernanceTokenId: String,
    stakePoolSize: Long,
    governanceType: GovernanceType.Value,
    quorum: Long,
    threshold: Long,
    stakingEmissionAmount: Long,
    stakingEmissionDelay: Byte,
    stakingCycleLength: Long,
    stakingProfitSharePct: Byte,
    userAddresses: Array[String],
    pureParticipationWeight: Byte,
    participationWeight: Byte
)

object CreateDAORequest {
  implicit val readsGovernanceType = Reads.enumNameReads(GovernanceType)
  implicit val writesGovernanceType = Writes.enumNameWrites

  implicit val json = Json.format[CreateDAORequest]
}
