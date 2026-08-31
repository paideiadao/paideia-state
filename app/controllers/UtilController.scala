package controllers

import javax.inject._
import play.api.mvc.ControllerComponents
import scala.concurrent.ExecutionContext
import play.api.mvc.BaseController
import play.api.mvc.Request
import play.api.libs.json.Json
import play.api.mvc.AnyContent
import play.api.libs.json.JsError
import scala.concurrent.Future
import play.api.libs.json.JsSuccess
import actors.PaideiaStateActor
import im.paideia.common.contracts.PaideiaContractSignature
import scala.util.Success
import scala.util.Failure
import models.GetContractSignatureRequest
import models.ContractSigModel
import im.paideia.common.contracts.PaideiaContract

@Singleton
class UtilController @Inject() (
    service: services.PaideiaStateService,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController {

  def getContractSignature = Action.async {
    implicit request: Request[AnyContent] =>
      val content = request.body
      val jsonObject = content.asJson
      val getContractSigRequest =
        Json.fromJson[GetContractSignatureRequest](jsonObject.get)

      getContractSigRequest match {
        case je: JsError => Future(BadRequest(JsError.toJson(je)))
        case js: JsSuccess[GetContractSignatureRequest] =>
          val getContractSig: GetContractSignatureRequest = js.value
          Future(
            service.getContractSignature(
              PaideiaStateActor.GetContractSignature(
                contractHash = getContractSig.contractHash,
                contractAddress = getContractSig.contractAddress,
                contractClass = getContractSig.contractClass,
                contractDaoKey = getContractSig.contractDaoKey,
                contractVersion = getContractSig.contractVersion
              )
            )
          )
            .map(paideiaContractTry =>
              paideiaContractTry match {
                case Success(paideiaContract) =>
                  Ok(Json.toJson(ContractSigModel(paideiaContract)))
                case Failure(exception) => BadRequest(exception.getMessage())
              }
            )
      }
  }
}
