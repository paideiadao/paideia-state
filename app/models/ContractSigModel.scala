package models

import im.paideia.common.contracts.PaideiaContractSignature
import play.api.libs.json.Json

final case class ContractSigModel(
    className: String,
    daoKey: String,
    version: String,
    hash: String
)

object ContractSigModel {
  implicit val json = Json.format[ContractSigModel]
  def apply(contractSig: PaideiaContractSignature): ContractSigModel =
    ContractSigModel(
      className = contractSig.className,
      daoKey = contractSig.daoKey,
      version = contractSig.version,
      hash = contractSig.contractHash.map("%02x" format _).mkString
    )
}
