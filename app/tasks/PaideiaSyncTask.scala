package tasks

import javax.inject.Inject
import javax.inject.Named
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import akka.actor.ActorRef
import org.ergoplatform.appkit.RestApiErgoClient
import im.paideia.util.Env
import scala.collection.mutable
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import play.api.Logging
import akka.pattern.ask
import actors.PaideiaStateActor._
import im.paideia.common.events.BlockEvent
import org.ergoplatform.appkit.BlockchainContext
import scala.concurrent.Future
import play.api.mvc.Result
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import akka.util._
import im.paideia.common.events.PaideiaEventResponse
import scala.concurrent.Await
import im.paideia.common.events.TransactionEvent
import org.ergoplatform.restapi.client.ErgoTransaction
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import im.paideia.Paideia
import im.paideia.common.contracts.PaideiaContractSignature
import im.paideia.staking.contracts.StakeProxy
import im.paideia.staking.transactions.StakeTransaction
import im.paideia.common.transactions.PaideiaTransaction
import im.paideia.common.contracts.Treasury
import im.paideia.common.events.CreateTransactionsEvent
import im.paideia.staking.transactions.SplitProfitTransaction
import im.paideia.staking.transactions.UnstakeTransaction
import org.ergoplatform.sdk.ErgoId
import scala.collection.JavaConverters._
import im.paideia.staking.contracts.AddStakeProxy
import im.paideia.staking.transactions.EmitTransaction
import im.paideia.staking.transactions.CompoundTransaction
import models.MUnsignedTransaction
import play.api.libs.json.Json
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import sigmastate.serialization.ValueSerializer
import scorex.util.encode.Base16
import scala.reflect.io.File

class PaideiaSyncTask @Inject() (
    @Named("paideia-state") paideiaActor: ActorRef,
    actorSystem: ActorSystem
)(implicit
    ec: ExecutionContext
) extends Logging {

  implicit val timeout: Timeout = 5.seconds

  var currentHeight = Env.conf.getInt("syncStart")
  var mempoolTransactions = mutable.HashMap[String, ErgoTransaction]()

  actorSystem.scheduler.scheduleWithFixedDelay(
    initialDelay = 5.seconds,
    delay = 20.seconds
  )(() => {

    try {
      logger.info(
        s"""Checking blockchain, syncer current height: ${currentHeight.toString}"""
      )

      val ergoClient = RestApiErgoClient.create(
        Env.conf.getString("node"),
        Env.networkType,
        "",
        Env.conf.getString("explorer")
      )

      ergoClient.execute(
        new java.util.function.Function[BlockchainContext, Unit] {
          override def apply(_ctx: BlockchainContext): Unit = {

            val ctx = _ctx.asInstanceOf[BlockchainContextImpl]

            val datasource =
              ergoClient
                .getDataSource()
                .asInstanceOf[NodeAndExplorerDataSourceImpl]

            val nodeHeight =
              datasource
                .getNodeInfoApi()
                .getNodeInfo()
                .execute()
                .body()
                .getFullHeight()

            if (currentHeight < nodeHeight)
              logger.info(s"""Node height: ${nodeHeight.toString()}""")

            while (currentHeight < nodeHeight) {
              val blockHeaderId = datasource
                .getNodeBlocksApi()
                .getFullBlockAt(currentHeight)
                .execute()
                .body()
                .get(0)
              val fullBlock = datasource
                .getNodeBlocksApi()
                .getFullBlockById(blockHeaderId)
                .execute()
                .body()
              val txs = fullBlock
                .getBlockTransactions()
                .getTransactions()
                .asScala
              val outputs =
                txs
                  .flatMap(t => t.getOutputs().asScala)
                  .map(eto => eto.getBoxId())

              while (txs.toArray.length > 0) {
                val handledTxs = mutable.Buffer[ErgoTransaction]()
                txs.foreach(et =>
                  if (
                    et.getInputs()
                      .asScala
                      .forall(eti => !outputs.contains(eti.getBoxId()))
                  ) {
                    Await.result(
                      (paideiaActor ? BlockchainEvent(
                        TransactionEvent(
                          ctx,
                          false,
                          et,
                          fullBlock.getHeader().getHeight()
                        )
                      ))
                        .mapTo[Try[PaideiaEventResponse]]
                        .map(per =>
                          per match {
                            case Success(resp) =>
                              resp.exceptions
                                .foreach(e => {

                                  logger.error(e.getMessage(), e)

                                })

                            case Failure(exception) =>
                              logger.error(exception.getMessage(), exception)

                          }
                        ),
                      5.seconds
                    )

                    et.getOutputs()
                      .asScala
                      .foreach(eto => outputs -= eto.getBoxId())
                    handledTxs += et
                  }
                )

                handledTxs.foreach(et => txs -= et)

                if (txs.toArray.length > 0) logger.info("Reordering needed")
              }
              currentHeight += 1
              if (currentHeight % 100 == 0)
                logger.info(
                  s"""Syncer current height: ${currentHeight.toString}"""
                )
            }

            var offset = 0
            val limit = 50
            var resultSize = limit
            var newMempoolTransactions =
              mutable.HashMap[String, ErgoTransaction]()

            while (limit == resultSize) {
              val memTransactions =
                datasource
                  .getNodeTransactionsApi()
                  .getUnconfirmedTransactions(limit, offset)
                  .execute()
                  .body()
              resultSize = memTransactions.size()
              offset += limit
              memTransactions.forEach(t => {
                if (!mempoolTransactions.contains(t.getId())) {
                  Await.result(
                    (paideiaActor ? BlockchainEvent(
                      TransactionEvent(ctx, true, t)
                    ))
                      .mapTo[Try[PaideiaEventResponse]]
                      .map(per =>
                        per match {
                          case Success(resp) =>
                            resp.exceptions
                              .foreach(e => logger.error(e.getMessage(), e))
                          case Failure(exception) =>
                            logger.error(exception.getMessage(), exception)
                        }
                      ),
                    5.seconds
                  )
                }
                newMempoolTransactions(t.getId()) = t
              })
            }

            mempoolTransactions.foreach(kv =>
              if (!newMempoolTransactions.contains(kv._1)) {
                Await.result(
                  (paideiaActor ? BlockchainEvent(
                    TransactionEvent(ctx, true, kv._2, rollback = true)
                  ))
                    .mapTo[Try[PaideiaEventResponse]]
                    .map(per =>
                      per match {
                        case Success(resp) =>
                          resp.exceptions
                            .foreach(e => logger.error(e.getMessage(), e))
                        case Failure(exception) =>
                          logger.error(exception.getMessage(), exception)
                      }
                    ),
                  5.seconds
                )
              }
            )

            mempoolTransactions = newMempoolTransactions

            val orphanedTxs = mutable.Buffer[String]()
            val orphanedOutputs = mutable.Buffer[String]()
            var foundNewOrphan = true

            while (foundNewOrphan) {
              foundNewOrphan = false
              mempoolTransactions.foreach(kv => {
                if (!orphanedTxs.contains(kv._1))
                  if (
                    !kv._2
                      .getInputs()
                      .asScala
                      .forall(eti =>
                        try {
                          if (orphanedOutputs.contains(eti.getBoxId()))
                            false
                          else {
                            ctx
                              .getDataSource()
                              .getBoxById(eti.getBoxId(), true, false)
                            true
                          }
                        } catch { case e: Exception => false }
                      )
                  ) {
                    logger.info(
                      s"""Found orphan tx in mempool, rolling back: ${kv._1}"""
                    )
                    orphanedTxs += kv._1
                    orphanedOutputs ++= kv._2
                      .getOutputs()
                      .asScala
                      .map(eto => eto.getBoxId())
                    foundNewOrphan = true
                  }
              })
            }

            orphanedTxs.foreach(txId =>
              Await.result(
                (paideiaActor ? BlockchainEvent(
                  TransactionEvent(
                    ctx,
                    true,
                    mempoolTransactions(txId),
                    rollback = true
                  )
                ))
                  .mapTo[Try[PaideiaEventResponse]]
                  .map(per =>
                    per match {
                      case Success(resp) =>
                        resp.exceptions
                          .foreach(e => logger.error(e.getMessage(), e))
                      case Failure(exception) =>
                        logger.error(exception.getMessage(), exception)
                    }
                  ),
                5.seconds
              )
            )

            var usedInputs = List[ErgoId]()

            Await.result(
              (paideiaActor ? BlockchainEvent(
                CreateTransactionsEvent(
                  ctx,
                  ctx
                    .getHeaders()
                    .get(0)
                    .getTimestamp(),
                  currentHeight
                )
              ))
                .mapTo[Try[PaideiaEventResponse]]
                .map(per =>
                  per match {
                    case Success(resp) => {
                      logger.info(resp.toString())
                      resp.unsignedTransactions.foreach(ut => {
                        if (
                          ut.inputs.forall(b => !usedInputs.contains(b.getId()))
                        )
                          try {
                            ut match {
                              case t: PaideiaTransaction =>
                                logger
                                  .info(
                                    s"""Attempting to sign transaction type: ${ut
                                        .getClass()
                                        .getCanonicalName()}"""
                                  )
                                try {
                                  ctx.sendTransaction(
                                    ctx
                                      .newProverBuilder()
                                      .build()
                                      .sign(ut.unsigned())
                                  )
                                  usedInputs =
                                    usedInputs ++ ut.inputs.map(b => b.getId())
                                } catch {
                                  case e: Exception =>
                                    logger.error(
                                      Json
                                        .toJson(
                                          MUnsignedTransaction(ut.unsigned())
                                        )
                                        .toString()
                                    )
                                    logger.error(e.getMessage())
                                }
                            }
                          } catch {
                            case e: Exception => logger.error(e.getMessage(), e)
                          }
                      })
                      resp.exceptions.map(e => {
                        logger.error(e.getMessage(), e)
                      })
                    }
                    case Failure(exception) =>
                      logger.error(exception.getMessage(), exception)
                  }
                ),
              5.seconds
            )

          }
        }
      )
    } catch {
      case e: Exception => logger.error(e.getMessage(), e)
    }
  })
}
