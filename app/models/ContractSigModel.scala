package models

import im.paideia.common.contracts.PaideiaContractSignature
import play.api.libs.json.Json
import im.paideia.common.contracts.PaideiaContract

final case class ContractSigModel(
    className: String,
    daoKey: String,
    version: String,
    hash: String,
    address: String
)

object ContractSigModel {
  implicit val json = Json.format[ContractSigModel]
  def apply(contract: PaideiaContract): ContractSigModel =
    ContractSigModel(
      className = contract.contractSignature.className,
      daoKey = contract.contractSignature.daoKey,
      version = contract.contractSignature.version,
      hash =
        contract.contractSignature.contractHash.map("%02x" format _).mkString,
      address = contract.contract.toAddress().getErgoAddress().toString()
    )
}
