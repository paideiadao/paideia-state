package models

import play.api.libs.json.Json

final case class GetContractSignatureRequest(
    contractHash: Option[List[Byte]],
    contractAddress: Option[String],
    contractClass: Option[String],
    contractDaoKey: Option[String],
    contractVersion: Option[String]
)

object GetContractSignatureRequest {
  implicit val json = Json.format[GetContractSignatureRequest]
}
