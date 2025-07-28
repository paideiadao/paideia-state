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
import scorex.util.encode.Base16
import scala.reflect.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughCoinsForChangeException
import org.zeromq.ZContext
import org.zeromq.SocketType
import org.ergoplatform.appkit.ErgoClient
import im.paideia.governance.transactions.EvaluateProposalBasicTransaction
import im.paideia.governance.transactions.UpdateConfigTransaction

class UnsignedTransactionException(
    val transactionJson: String,
    val innerException: Exception
) extends Exception {
  override def getMessage(): String =
    innerException.getMessage() ++ transactionJson
  override def getStackTrace(): Array[StackTraceElement] =
    innerException.getStackTrace()
}

class PaideiaSyncTask @Inject() (
    @Named("paideia-state") paideiaActor: ActorRef,
    @Named("paideia-archive") archiveActor: ActorRef,
    @Named("error-logging") errorActor: ActorRef,
    actorSystem: ActorSystem
)(implicit
    ec: ExecutionContext
) extends Logging {

  implicit val timeout: Timeout = 25.seconds

  val virtualMempoolHeight: Int = 10

  var currentHeight = Env.conf.getInt("syncStart")
  var syncing = true
  var mempoolTransactions = mutable.HashMap[String, ErgoTransaction]()

  actorSystem.getScheduler.scheduleWithFixedDelay(
    initialDelay = 5.seconds,
    delay = 5.seconds
  )(() =>
    try {
      val daoconfigdir = File("./daoconfigs/").toAbsolute.toDirectory
      if (!daoconfigdir.exists)
        daoconfigdir.createDirectory()

      val stakingStatesDir = File("./stakingStates/").toAbsolute.toDirectory

      if (!stakingStatesDir.exists)
        stakingStatesDir.createDirectory()

      val proposalsDir = File("./proposals/").toAbsolute.toDirectory

      if (!proposalsDir.exists)
        proposalsDir.createDirectory()
      logger.info(
        s"""Checking blockchain, syncer current height: ${currentHeight.toString}"""
      )

      val ergoClient = RestApiErgoClient.create(
        Env.conf.getString("node"),
        Env.networkType,
        "",
        Env.conf.getString("explorer")
      )

      if (syncing) {
        syncFromArchive(ergoClient)
      }

      syncRemainingBlocks(ergoClient)

      syncing = false

      syncMempool(ergoClient)

      val zContext: ZContext = new ZContext()
      val socket = zContext.createSocket(SocketType.SUB)
      val zeroMQIP = Env.conf.getString("zmqHost")
      val zeroMQPort = Env.conf.getString("zmqPort")
      socket.connect(f"tcp://${zeroMQIP}:${zeroMQPort}")
      socket.subscribe("mempool")
      socket.subscribe("newBlock")

      generateTransactions(ergoClient)

      while (true) {
        val message = socket.recvStr()
        if (message == "newBlock") {
          logger.info("New block")
          val blockHeader = socket.recvStr()
          syncRemainingBlocks(ergoClient)
          syncMempool(ergoClient)
          generateTransactions(ergoClient)
        }
        if (message == "mempool") {
          logger.info("New mempool transaction")
          val transactionId = socket.recvStr()
          if (syncMempoolTransaction(ergoClient, transactionId))
            generateTransactions(ergoClient)
        }
      }

    } catch {
      case e: Exception => {
        logger.error(e.getStackTrace().map(_.toString()).mkString)
        logger.error(e.getMessage(), e)
      }
    }
  )

  def syncMempoolTransaction(
      ergoClient: ErgoClient,
      transactionId: String
  ) = {
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Boolean] {
        override def apply(_ctx: BlockchainContext): Boolean = {

          val ctx = _ctx.asInstanceOf[BlockchainContextImpl]
          val datasource =
            ergoClient
              .getDataSource()
              .asInstanceOf[NodeAndExplorerDataSourceImpl]
          val resp = datasource
            .getNodeTransactionsApi()
            .getUnconfirmedTransactionById(transactionId)
            .execute()
          if (resp.isSuccessful()) {
            val t = resp
              .body()
            val eventResponse = Await.result(
              (paideiaActor ? BlockchainEvent(
                TransactionEvent(ctx, true, t),
                syncing
              ))
                .mapTo[Try[PaideiaEventResponse]]
                .map(per => {
                  per match {
                    case Success(resp) =>
                      resp.exceptions
                        .foreach(e => (errorActor ! e))
                    case Failure(exception) =>
                      logger.error(exception.getMessage(), exception)
                  }
                  per
                }),
              5.seconds
            )

            mempoolTransactions(t.getId()) = t
            logger.info(f"Response to mempool tx: ${eventResponse.get}")
            eventResponse.isSuccess && eventResponse.get.status >= 1
          } else {
            logger.info(
              f"Failed fetching mempool tx ${transactionId}: ${resp.toString()}"
            )
            false
          }
        }
      }
    )
  }

  def generateTransactions(ergoClient: ErgoClient) = {
    ergoClient.execute(new java.util.function.Function[BlockchainContext, Unit] {
      override def apply(_ctx: BlockchainContext): Unit = {
        val datasource =
          ergoClient
            .getDataSource()
            .asInstanceOf[NodeAndExplorerDataSourceImpl]
        val ctx = _ctx.asInstanceOf[BlockchainContextImpl]
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
            ),
            syncing
          ))
            .mapTo[Try[PaideiaEventResponse]]
            .map(per =>
              per match {
                case Success(resp) => {
                  logger.info(resp.toString())
                  resp.unsignedTransactions.foreach(ut => {
                    if (ut.inputs.forall(b => !usedInputs.contains(b.getId())))
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
                                try {
                                  (errorActor ! new UnsignedTransactionException(
                                    Json
                                      .toJson(
                                        MUnsignedTransaction(ut.unsigned())
                                      )
                                      .toString(),
                                    e
                                  ))
                                } catch {
                                  case e: Exception => (errorActor ! e)
                                }
                            }
                        }
                      } catch {
                        case e: Exception => (errorActor ! e)
                      }
                  })
                  resp.exceptions.map(e => {
                    (errorActor ! e)
                  })
                }
                case Failure(exception) =>
                  logger.error(exception.getMessage(), exception)
              }
            ),
          30.seconds
        )
      }
    })
  }

  def syncMempool(
      ergoClient: ErgoClient
  ) = {
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Unit] {
        override def apply(_ctx: BlockchainContext): Unit = {
          val datasource =
            ergoClient
              .getDataSource()
              .asInstanceOf[NodeAndExplorerDataSourceImpl]
          val ctx = _ctx.asInstanceOf[BlockchainContextImpl]
          var offset = 0
          val limit = 50
          var resultSize = limit
          var newMempoolTransactions =
            mutable.HashMap[String, ErgoTransaction]()

          var nodeHeight =
            datasource
              .getNodeInfoApi()
              .getNodeInfo()
              .execute()
              .body()
              .getFullHeight()

          var virtualCurrentHeight = currentHeight

          var blockAwaitable = Future {
            val blockHeaderId = datasource
              .getNodeBlocksApi()
              .getFullBlockAt(virtualCurrentHeight)
              .execute()
              .body()
              .get(0);
            datasource
              .getNodeBlocksApi()
              .getFullBlockById(blockHeaderId)
              .execute()
              .body()
          };

          while (virtualCurrentHeight <= nodeHeight) {
            val fullBlock =
              Await.result(blockAwaitable, 20.seconds)
            if (virtualCurrentHeight + 1 < nodeHeight)
              blockAwaitable = Future {
                val blockHeaderId = datasource
                  .getNodeBlocksApi()
                  .getFullBlockAt(virtualCurrentHeight + 1)
                  .execute()
                  .body()
                  .get(0);
                datasource
                  .getNodeBlocksApi()
                  .getFullBlockById(blockHeaderId)
                  .execute()
                  .body()
              };
            val txs = fullBlock
              .getBlockTransactions()
              .getTransactions()
              .asScala
            txs.foreach(et => {
              if (!mempoolTransactions.contains(et.getId())) {
                logger.info(
                  s"""Syncing virtual mempool transaction: ${et.getId()}"""
                )
                Await.result(
                  (paideiaActor ? BlockchainEvent(
                    TransactionEvent(ctx, true, et),
                    syncing
                  ))
                    .mapTo[Try[PaideiaEventResponse]]
                    .map(per =>
                      per match {
                        case Success(resp) =>
                          resp.exceptions
                            .foreach(e => (errorActor ! e))
                        case Failure(exception) =>
                          logger.error(exception.getMessage(), exception)
                      }
                    ),
                  5.seconds
                )
              }
              newMempoolTransactions(et.getId()) = et
            })
            virtualCurrentHeight += 1
            if (virtualCurrentHeight >= nodeHeight)
              nodeHeight = datasource
                .getNodeInfoApi()
                .getNodeInfo()
                .execute()
                .body()
                .getFullHeight()
          }

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
                logger.info(
                  s"""Syncing mempool transaction from mempool: ${t.getId()}"""
                )
                Await.result(
                  (paideiaActor ? BlockchainEvent(
                    TransactionEvent(ctx, true, t),
                    syncing
                  ))
                    .mapTo[Try[PaideiaEventResponse]]
                    .map(per =>
                      per match {
                        case Success(resp) =>
                          resp.exceptions
                            .foreach(e => (errorActor ! e))
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
              logger.info(
                s"""Rolling back mempool transaction: ${kv._1}"""
              )
              Await.result(
                (paideiaActor ? BlockchainEvent(
                  TransactionEvent(ctx, true, kv._2, rollback = true),
                  syncing
                ))
                  .mapTo[Try[PaideiaEventResponse]]
                  .map(per =>
                    per match {
                      case Success(resp) =>
                        resp.exceptions
                          .foreach(e => (errorActor ! e))
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
                          true
                        }
                      } catch { case e: Exception => false }
                    ) ||
                  !kv._2
                    .getOutputs()
                    .asScala
                    .forall(eto => {
                      try {
                        ctx
                          .getDataSource()
                          .getBoxById(eto.getBoxId(), true, true)
                        true
                      } catch {
                        case e: Exception => false
                      }
                    })
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
                ),
                syncing
              ))
                .mapTo[Try[PaideiaEventResponse]]
                .map(per =>
                  per match {
                    case Success(resp) =>
                      resp.exceptions
                        .foreach(e => (errorActor ! e))
                    case Failure(exception) =>
                      logger.error(exception.getMessage(), exception)
                  }
                ),
              5.seconds
            )
          )
        }
      }
    )
  }

  def syncRemainingBlocks(
      ergoClient: ErgoClient
  ) = {
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Unit] {
        override def apply(_ctx: BlockchainContext): Unit = {
          val datasource =
            ergoClient
              .getDataSource()
              .asInstanceOf[NodeAndExplorerDataSourceImpl]
          val ctx = _ctx.asInstanceOf[BlockchainContextImpl]
          var nodeHeight =
            datasource
              .getNodeInfoApi()
              .getNodeInfo()
              .execute()
              .body()
              .getFullHeight()

          logger.info(s"""Node height: ${nodeHeight
              .toString()} Current height: ${currentHeight.toString()}""")

          var blockAwaitable = Future {
            val blockHeaderId = datasource
              .getNodeBlocksApi()
              .getFullBlockAt(currentHeight)
              .execute()
              .body()
              .get(0);
            datasource
              .getNodeBlocksApi()
              .getFullBlockById(blockHeaderId)
              .execute()
              .body()
          };

          while (currentHeight < (nodeHeight - virtualMempoolHeight)) {
            val fullBlock =
              Await.result(blockAwaitable, 20.seconds)
            if (currentHeight + 1 < (nodeHeight - virtualMempoolHeight))
              blockAwaitable = Future {
                val blockHeaderId = datasource
                  .getNodeBlocksApi()
                  .getFullBlockAt(currentHeight + 1)
                  .execute()
                  .body()
                  .get(0);
                datasource
                  .getNodeBlocksApi()
                  .getFullBlockById(blockHeaderId)
                  .execute()
                  .body()
              };
            val txs = fullBlock
              .getBlockTransactions()
              .getTransactions()
              .asScala
            txs.foreach(et => {
              val event = TransactionEvent(
                ctx,
                false,
                et,
                fullBlock.getHeader().getHeight()
              )
              Await.result(
                (paideiaActor ? BlockchainEvent(
                  event,
                  syncing
                ))
                  .mapTo[Try[PaideiaEventResponse]]
                  .map(per =>
                    per match {
                      case Success(resp) => {
                        if (resp.status > 0) (archiveActor ? event)
                        resp.exceptions
                          .foreach(e => {

                            logger.error(e.getMessage(), e)
                            throw e

                          })
                      }

                      case Failure(exception) =>
                        logger.error(exception.getMessage(), exception)

                    }
                  ),
                30.seconds
              )
            })
            currentHeight += 1
            if (currentHeight >= (nodeHeight - virtualMempoolHeight))
              nodeHeight = datasource
                .getNodeInfoApi()
                .getNodeInfo()
                .execute()
                .body()
                .getFullHeight()
            if (currentHeight % 100 == 0)
              logger.info(
                s"""Syncer current height: ${currentHeight.toString}"""
              )
          }
        }
      }
    )
  }

  def syncFromArchive(ergoClient: ErgoClient) =
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Unit] {
        override def apply(_ctx: BlockchainContext): Unit = {

          val ctx = _ctx.asInstanceOf[BlockchainContextImpl]
          val archivedTransactionFiles = Files
            .list(Paths.get("transaction_archive"))
            .iterator()
            .asScala
            .filter(Files.isRegularFile(_))
            .toSeq
            .sorted

          archivedTransactionFiles.foreach((p) => {
            logger.info(p.toString())
            val height = p.getFileName().toString().toInt
            if (height >= currentHeight) {
              val transactions: Array[ErgoTransaction] =
                new Gson().fromJson(
                  Files.readString(p, StandardCharsets.UTF_8),
                  classOf[Array[ErgoTransaction]]
                )
              transactions.foreach((et) => {
                val event = TransactionEvent(
                  ctx,
                  false,
                  et,
                  height
                )
                Await.result(
                  (paideiaActor ? BlockchainEvent(
                    event,
                    syncing
                  ))
                    .mapTo[Try[PaideiaEventResponse]]
                    .map(per =>
                      per match {
                        case Success(resp) => {
                          resp.exceptions
                            .foreach(e => {
                              (errorActor ! e)
                            })
                        }

                        case Failure(exception) =>
                          logger.error(exception.getMessage(), exception)

                      }
                    ),
                  30.seconds
                )
              })
            }
            currentHeight = height + 1
          })
        }
      }
    )
}
