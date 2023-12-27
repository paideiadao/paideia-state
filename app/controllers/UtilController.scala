package controllers

import javax.inject._
import akka.actor.ActorRef
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
import akka.pattern.ask
import akka.util._
import scala.concurrent.duration._
import scala.util.Try
import im.paideia.common.contracts.PaideiaContractSignature
import scala.util.Success
import scala.util.Failure
import models.GetContractSignatureRequest
import models.ContractSigModel

@Singleton
class UtilController @Inject() (
    @Named("paideia-state") paideiaActor: ActorRef,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController {

  implicit val timeout: Timeout = 5.seconds

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
          (paideiaActor ? PaideiaStateActor.GetContractSignature(
            contractHash = getContractSig.contractHash,
            contractAddress = getContractSig.contractAddress,
            contractClass = getContractSig.contractClass,
            contractDaoKey = getContractSig.contractDaoKey,
            contractVersion = getContractSig.contractVersion
          ))
            .mapTo[Try[PaideiaContractSignature]]
            .map(paideiaContractSigTry =>
              paideiaContractSigTry match {
                case Success(paideiaContractSig) =>
                  Ok(Json.toJson(ContractSigModel(paideiaContractSig)))
                case Failure(exception) => BadRequest(exception.getMessage())
              }
            )
      }
  }
}
