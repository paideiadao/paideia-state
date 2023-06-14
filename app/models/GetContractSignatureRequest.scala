package models

import play.api.libs.json.Json

final case class GetContractSignatureRequest(
    contractHash: Option[List[Byte]],
    contractAddress: Option[String]
)

object GetContractSignatureRequest {
  implicit val json = Json.format[GetContractSignatureRequest]
}
