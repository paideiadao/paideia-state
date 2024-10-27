package controllers

import javax.inject._
import akka.actor.ActorRef
import play.api.mvc.ControllerComponents
import scala.concurrent.ExecutionContext
import play.api.mvc.BaseController
import akka.util._
import org.ergoplatform.appkit.RestApiErgoClient
import im.paideia.util.Env
import scala.concurrent.duration._
import play.api.mvc.Request
import play.api.libs.json.Json
import play.api.mvc.AnyContent
import models.StakeRequest
import scala.concurrent.Future
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import org.ergoplatform.appkit.BlockchainContext
import play.api.mvc.Result
import akka.pattern.ask
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import actors.PaideiaStateActor._
import org.ergoplatform.appkit.OutBox
import models.MUnsignedTransaction
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.Address
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughTokensException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughErgsException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughCoinsForChangeException
import models.AddStakeRequest
import models.UnstakeRequest
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import play.api.Logging
import org.ergoplatform.explorer.client.model
import im.paideia.staking.StakeRecord
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.impl.NodeDataSourceImpl
import org.ergoplatform.restapi.client.ApiClient
import okhttp3.OkHttpClient
import models.GetStakesRequest
import im.paideia.common.transactions.PaideiaTransaction

@Singleton
class StakeController @Inject() (
    @Named("paideia-state") paideiaActor: ActorRef,
    @Named("error-logging") errorActor: ActorRef,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController
    with Logging {

  implicit val timeout: Timeout = 5.seconds

  def createErgoClient = RestApiErgoClient.create(
    Env.conf.getString("node"),
    Env.networkType,
    "",
    Env.conf.getString("explorer")
  )

  def getStake(daoKey: String, stakeKey: String) = Action.async {
    implicit request: Request[AnyContent] =>
      createErgoClient.execute(
        new java.util.function.Function[BlockchainContext, Future[Result]] {
          override def apply(_ctx: BlockchainContext): Future[Result] = {
            (paideiaActor ? GetStake(
              daoKey,
              List(stakeKey),
              _ctx.asInstanceOf[BlockchainContextImpl]
            ))
              .mapTo[Try[List[StakeInfo]]]
              .map(stakeRecordTry =>
                stakeRecordTry match {
                  case Success(stakeRecord) =>
                    if (stakeRecord.length > 0) Ok(Json.toJson(stakeRecord(0)))
                    else { NotFound }
                  case Failure(exception) => {
                    (errorActor ! exception)
                    BadRequest(exception.getMessage())
                  }
                }
              )
          }
        }
      )
  }

  def getStakes(daoKey: String) = Action.async {
    implicit request: Request[AnyContent] =>
      val content = request.body
      val jsonObject = content.asJson
      val stakeRequest = Json.fromJson[GetStakesRequest](jsonObject.get)
      stakeRequest match {
        case je: JsError => Future(BadRequest(JsError.toJson(je)))
        case js: JsSuccess[GetStakesRequest] =>
          val stakeKeys: GetStakesRequest = js.value
          createErgoClient.execute(
            new java.util.function.Function[BlockchainContext, Future[Result]] {
              override def apply(_ctx: BlockchainContext): Future[Result] = {
                (paideiaActor ? GetStake(
                  daoKey,
                  stakeKeys.potentialKeys,
                  _ctx.asInstanceOf[BlockchainContextImpl]
                ))
                  .mapTo[Try[List[StakeInfo]]]
                  .map(stakeRecordTry =>
                    stakeRecordTry match {
                      case Success(stakeRecord) =>
                        Ok(Json.toJson(stakeRecord))
                      case Failure(exception) => {
                        (errorActor ! exception)
                        BadRequest(exception.getMessage())
                      }
                    }
                  )
              }
            }
          )
      }
  }

  def getDaoStake(daoKey: String) = Action.async {
    implicit request: Request[AnyContent] =>
      createErgoClient.execute(
        new java.util.function.Function[BlockchainContext, Future[Result]] {
          override def apply(_ctx: BlockchainContext): Future[Result] = {
            (paideiaActor ? GetDaoStake(
              _ctx.asInstanceOf[BlockchainContextImpl],
              daoKey
            ))
              .mapTo[Try[DaoStakeInfo]]
              .map(stakeRecordTry =>
                stakeRecordTry match {
                  case Success(stakeRecord) => Ok(Json.toJson(stakeRecord))
                  case Failure(exception) => {
                    (errorActor ! exception)
                    BadRequest(exception.getMessage())
                  }
                }
              )
          }
        }
      )
  }

  def stake = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val stakeRequest = Json.fromJson[StakeRequest](jsonObject.get)

    stakeRequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[StakeRequest] =>
        val stake: StakeRequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? StakeTransactionRequest(
                _ctx.asInstanceOf[BlockchainContextImpl],
                stake.daoKey,
                stake.userAddress,
                stake.stakeAmount
              )).mapTo[Try[PaideiaTransaction]]
                .map(outBoxTry =>
                  outBoxTry match {
                    case Failure(exception) => {
                      (errorActor ! exception)
                      BadRequest(exception.getMessage())
                    }
                    case Success(paideiaTransaction) =>
                      try {
                        Ok(
                          Json.toJson(
                            MUnsignedTransaction(
                              paideiaTransaction,
                              stake.userAddresses,
                              Env.conf.getLong("uiFeeStakeOp")
                            )
                          )
                        )
                      } catch {
                        case nete: NotEnoughTokensException =>
                          BadRequest(
                            "The wallet did not contain the tokens required for staking"
                          )
                        case neee: NotEnoughErgsException =>
                          BadRequest(
                            "Not enough erg in wallet for staking"
                          )
                        case necfc: NotEnoughCoinsForChangeException =>
                          BadRequest(
                            "Not enough erg for change box, try consolidating your utxos to remove this error"
                          )
                        case e: Exception => {
                          (errorActor ! e)
                          BadRequest(e.getMessage())
                        }
                      }
                  }
                )
            }
          }
        )
    }
  }

  def addStake = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val addStakeRequest = Json.fromJson[AddStakeRequest](jsonObject.get)

    addStakeRequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[AddStakeRequest] =>
        val addStake: AddStakeRequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? AddStakeTransactionRequest(
                _ctx.asInstanceOf[BlockchainContextImpl],
                addStake.daoKey,
                addStake.stakeKey,
                addStake.userAddress,
                addStake.addStakeAmount
              )).mapTo[Try[PaideiaTransaction]]
                .map(outBoxTry =>
                  outBoxTry match {
                    case Failure(exception) => {
                      (errorActor ! exception)
                      BadRequest(exception.getMessage())
                    }
                    case Success(paideiaTransaction) =>
                      try {
                        Ok(
                          Json.toJson(
                            MUnsignedTransaction(
                              paideiaTransaction,
                              addStake.userAddresses,
                              Env.conf.getLong("uiFeeStakeOp")
                            )
                          )
                        )
                      } catch {
                        case nete: NotEnoughTokensException =>
                          BadRequest(
                            "The wallet did not contain the tokens required for adding stake"
                          )
                        case neee: NotEnoughErgsException =>
                          BadRequest(
                            "Not enough erg in wallet for adding stake"
                          )
                        case necfc: NotEnoughCoinsForChangeException =>
                          BadRequest(
                            "Not enough erg for change box, try consolidating your utxos to remove this error"
                          )
                        case e: Exception => {
                          (errorActor ! e)
                          BadRequest(e.getMessage())
                        }
                      }
                  }
                )
            }
          }
        )
    }
  }

  def unstake = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val unstakeRequest = Json.fromJson[UnstakeRequest](jsonObject.get)

    unstakeRequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[UnstakeRequest] =>
        val unstake: UnstakeRequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? UnstakeTransactionRequest(
                _ctx.asInstanceOf[BlockchainContextImpl],
                unstake.daoKey,
                unstake.stakeKey,
                unstake.userAddress,
                unstake.newStakeRecord
              )).mapTo[Try[PaideiaTransaction]]
                .map(outBoxTry =>
                  outBoxTry match {
                    case Failure(exception) => {
                      (errorActor ! exception)
                      BadRequest(exception.getMessage())
                    }
                    case Success(paideiaTransaction) =>
                      try {
                        Ok(
                          Json.toJson(
                            MUnsignedTransaction(
                              paideiaTransaction,
                              unstake.userAddresses,
                              Env.conf.getLong("uiFeeStakeOp")
                            )
                          )
                        )
                      } catch {
                        case nete: NotEnoughTokensException =>
                          BadRequest(
                            "The wallet did not contain the tokens required for unstaking"
                          )
                        case neee: NotEnoughErgsException =>
                          BadRequest(
                            "Not enough erg in wallet for unstaking"
                          )
                        case necfc: NotEnoughCoinsForChangeException =>
                          BadRequest(
                            "Not enough erg for change box, try consolidating your utxos to remove this error"
                          )
                        case e: Exception => {
                          (errorActor ! e)
                          BadRequest(e.getMessage())
                        }
                      }
                  }
                )
            }
          }
        )
    }
  }
}
