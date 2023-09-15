package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import akka.actor.ActorRef
import play.api.libs.json.Json
import models.CreateDAORequest
import models.MUnsignedTransaction
import org.ergoplatform.appkit.RestApiErgoClient
import im.paideia.util.Env
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import org.ergoplatform.appkit.BlockchainContext
import akka.pattern.ask
import actors.PaideiaStateActor._
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import akka.util._
import scala.concurrent.duration._
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.Address
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.OutBox
import scala.concurrent.ExecutionContext
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughTokensException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughErgsException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughCoinsForChangeException
import scala.concurrent.Future
import models.BootstrapRequest
import scala.util.Success
import scala.util.Try
import scala.util.Failure
import scala.collection.mutable.HashMap
import im.paideia.DAOConfigKey
import im.paideia.DAOConfigValueDeserializer
import models.ProposalBase
import models.DaoConfigValueEntry

/** This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class DAOController @Inject() (
    @Named("paideia-state") paideiaActor: ActorRef,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController {

  implicit val timeout: Timeout = 5.seconds

  def createErgoClient = RestApiErgoClient.create(
    Env.conf.getString("node"),
    Env.networkType,
    "",
    Env.conf.getString("explorer")
  )

  def getAllDAOs = Action.async { implicit request: Request[AnyContent] =>
    (paideiaActor ? GetAllDAOs())
      .mapTo[Try[HashMap[String, (String, Int)]]]
      .map(daoMapTry =>
        daoMapTry match {
          case Success(daoMap)    => Ok(Json.toJson(daoMap))
          case Failure(exception) => BadRequest(exception.getMessage())
        }
      )
  }

  def getDAOProposals(daoKey: String) = Action.async {
    implicit request: Request[AnyContent] =>
      (paideiaActor ? GetDAOProposals(daoKey))
        .mapTo[Try[List[(Int, String, Int)]]]
        .map(proposalListTry =>
          proposalListTry match {
            case Success(proposalList) =>
              Ok(
                Json.toJson(
                  proposalList.map(p =>
                    ProposalBase(
                      p._1,
                      p._2,
                      p._3
                    )
                  )
                )
              )
            case Failure(exception) => BadRequest(exception.getMessage())
          }
        )
  }

  def getDAOConfig(daoKey: String) = Action.async {
    implicit request: Request[AnyContent] =>
      (paideiaActor ? GetDAOConfig(daoKey))
        .mapTo[Try[Map[String, Array[Byte]]]]
        .map(configMapTry =>
          configMapTry match {
            case Success(configMap) =>
              Ok(
                Json.toJson(
                  configMap.map(cv =>
                    (
                      DaoConfigValueEntry(
                        cv._1,
                        DAOConfigValueDeserializer.getType(cv._2),
                        DAOConfigValueDeserializer.toString(cv._2)
                      )
                    )
                  )
                )
              )
            case Failure(exception) => BadRequest(exception.getMessage())
          }
        )
  }

  def getDAOTreasury(daoKey: String) = Action.async {
    implicit request: Request[AnyContent] =>
      (paideiaActor ? GetDAOTreasury(daoKey))
        .mapTo[Try[String]]
        .map(addressTry =>
          addressTry match {
            case Success(address) =>
              Ok(
                Json.toJson(
                  address
                )
              )
            case Failure(exception) => BadRequest(exception.getMessage())
          }
        )
  }

  def daoCreate = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val createDAORequest = Json.fromJson[CreateDAORequest](jsonObject.get)

    createDAORequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[CreateDAORequest] =>
        val createDAO: CreateDAORequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? CreateDAOBox(
                _ctx.asInstanceOf[BlockchainContextImpl],
                createDAO.daoName,
                createDAO.daoGovernanceTokenId,
                createDAO.stakePoolSize,
                createDAO.governanceType,
                createDAO.quorum,
                createDAO.threshold,
                createDAO.stakingEmissionAmount,
                createDAO.stakingEmissionDelay,
                createDAO.stakingCycleLength,
                createDAO.stakingProfitSharePct,
                createDAO.userAddresses(0),
                createDAO.pureParticipationWeight,
                createDAO.participationWeight
              )).mapTo[OutBox]
                .map(outBox =>
                  try {
                    Ok(
                      Json.toJson(
                        MUnsignedTransaction(
                          BoxOperations
                            .createForSenders(
                              createDAO.userAddresses
                                .map(addr => Address.create(addr))
                                .toList
                                .asJava,
                              _ctx
                            )
                            .withAmountToSpend(outBox.getValue())
                            .withTokensToSpend(outBox.getTokens())
                            .buildTxWithDefaultInputs(tb =>
                              tb.addOutputs(outBox)
                            )
                        )
                      )
                    )
                  } catch {
                    case nete: NotEnoughTokensException =>
                      BadRequest(
                        "The wallet did not contain the tokens required for bootstrapping"
                      )
                    case neee: NotEnoughErgsException =>
                      BadRequest("Not enough erg in wallet for bootstrapping")
                    case necfc: NotEnoughCoinsForChangeException =>
                      BadRequest(
                        "Not enough erg for change box, try consolidating your utxos to remove this error"
                      )
                    case e: Exception => BadRequest(e.getMessage())
                  }
                )
            }
          }
        )
    }
  }

  def bootstrap = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val bootstrapRequest = Json.fromJson[BootstrapRequest](jsonObject.get)

    bootstrapRequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[BootstrapRequest] =>
        val bootstrap: BootstrapRequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? Bootstrap(
                _ctx.asInstanceOf[BlockchainContextImpl],
                bootstrap.stakepoolSize
              )).mapTo[Array[OutBox]]
                .map(outBoxes =>
                  try {
                    Ok(
                      Json.toJson(
                        MUnsignedTransaction(
                          BoxOperations
                            .createForSenders(
                              bootstrap.userAddresses
                                .map(addr => Address.create(addr))
                                .toList
                                .asJava,
                              _ctx
                            )
                            .withAmountToSpend(
                              outBoxes.foldLeft(0L)((z: Long, b: OutBox) =>
                                b.getValue()
                              )
                            )
                            .withTokensToSpend(
                              outBoxes
                                .flatMap(_.getTokens().asScala)
                                .toList
                                .asJava
                            )
                            .buildTxWithDefaultInputs(tb =>
                              tb.addOutputs(outBoxes: _*)
                            )
                        )
                      )
                    )
                  } catch {
                    case nete: NotEnoughTokensException =>
                      BadRequest(
                        "The wallet did not contain the tokens required for bootstrapping"
                      )
                    case neee: NotEnoughErgsException =>
                      BadRequest("Not enough erg in wallet for bootstrapping")
                    case necfc: NotEnoughCoinsForChangeException =>
                      BadRequest(
                        "Not enough erg for change box, try consolidating your utxos to remove this error"
                      )
                    case e: Exception => BadRequest(e.getMessage())
                  }
                )
            }
          }
        )
    }
  }
}
