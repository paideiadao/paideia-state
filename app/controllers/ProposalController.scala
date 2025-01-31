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
import models.CreateProposalRequest
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import play.api.Logging
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoToken
import models.CastVoteRequest
import models.Proposal
import im.paideia.common.transactions.PaideiaTransaction
import org.ergoplatform.sdk.ExtendedInputBox
import org.ergoplatform.appkit.impl.InputBoxImpl

@Singleton
class ProposalController @Inject() (
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

  def getProposal(daoKey: String, index: Int) = Action.async {
    implicit request: Request[AnyContent] =>
      createErgoClient.execute(
        new java.util.function.Function[BlockchainContext, Future[Result]] {
          override def apply(_ctx: BlockchainContext): Future[Result] = {
            (paideiaActor ? GetDAOProposal(
              _ctx.asInstanceOf[BlockchainContextImpl],
              daoKey,
              index
            ))
              .mapTo[Try[Proposal]]
              .map(proposalTry =>
                proposalTry match {
                  case Success(proposal) =>
                    Ok(
                      Json.toJson(
                        proposal
                      )
                    )
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

  def castVote = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val castVoteRequest =
      Json.fromJson[CastVoteRequest](jsonObject.get)

    castVoteRequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[CastVoteRequest] =>
        val castVote: CastVoteRequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? CastVoteTransactionRequest(
                _ctx.asInstanceOf[BlockchainContextImpl],
                castVote.daoKey,
                castVote.stakeKey,
                castVote.proposalIndex,
                castVote.votes,
                castVote.userAddress
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
                              castVote.userAddresses,
                              Env.conf.getLong("uiFeeCastVote")
                            )
                          )
                        )
                      } catch {
                        case nete: NotEnoughTokensException =>
                          BadRequest(
                            "The wallet did not contain the tokens required for casting a vote"
                          )
                        case neee: NotEnoughErgsException =>
                          BadRequest(
                            "Not enough erg in wallet for casting a vote"
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

  def createProposal = Action.async { implicit request: Request[AnyContent] =>
    val content = request.body
    val jsonObject = content.asJson
    val createProposalRequest =
      Json.fromJson[CreateProposalRequest](jsonObject.get)

    createProposalRequest match {
      case je: JsError => Future(BadRequest(JsError.toJson(je)))
      case js: JsSuccess[CreateProposalRequest] =>
        val createProposal: CreateProposalRequest = js.value

        createErgoClient.execute(
          new java.util.function.Function[BlockchainContext, Future[Result]] {
            override def apply(_ctx: BlockchainContext): Future[Result] = {
              (paideiaActor ? CreateProposalTransactionRequest(
                _ctx.asInstanceOf[BlockchainContextImpl],
                createProposal.daoKey,
                createProposal.name,
                createProposal.endTime,
                createProposal.sendFundsActions.map(sfa =>
                  SendFundsAction(
                    sfa.optionId,
                    sfa.repeats,
                    sfa.repeatDelay,
                    sfa.activationTime,
                    sfa.outputs.map(sfao =>
                      SendFundsActionOutput(
                        sfao.address,
                        sfao.nergs,
                        sfao.tokens.map(kv => new ErgoToken(kv._1, kv._2)),
                        sfao.registers.map(ErgoValue.fromHex(_))
                      )
                    )
                  )
                ) ++ createProposal.updateConfigActions.map(uca =>
                  UpdateConfigAction(
                    uca.optionId,
                    uca.activationTime,
                    uca.remove,
                    uca.update,
                    uca.insert
                  )
                ),
                createProposal.voteKey,
                createProposal.userAddress
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
                              createProposal.userAddresses,
                              Env.conf.getLong("uiFeeCreateProposal")
                            )
                          )
                        )
                      } catch {
                        case nete: NotEnoughTokensException =>
                          BadRequest(
                            "The wallet did not contain the tokens required for creating a proposal"
                          )
                        case neee: NotEnoughErgsException =>
                          BadRequest(
                            "Not enough erg in wallet for creating a proposal"
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
