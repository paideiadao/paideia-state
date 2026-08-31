package tasks

import javax.inject.Inject
import javax.inject.Named
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

  /** Number of blocks to fetch ahead of the block currently being processed by
    * syncRemainingBlocks. Fetches run on a dedicated pool so the slow node HTTP
    * round-trips overlap instead of serializing; processing itself stays strictly
    * sequential and in height order.
    */
  val blockPrefetch: Int =
    if (Env.conf.hasPath("blockPrefetch")) Env.conf.getInt("blockPrefetch") else 8

  private val prefetchExecutorService = {
    val threadCount = new AtomicInteger(0)
    Executors.newFixedThreadPool(
      blockPrefetch,
      (r: Runnable) => {
        val t = new Thread(r, s"block-prefetch-${threadCount.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    )
  }

  private val prefetchEc: ExecutionContext =
    ExecutionContext.fromExecutorService(prefetchExecutorService)

  /** headerId -> FullBlock, shared by the prefetcher and the virtual mempool loop
    * so a block fetched by one is not re-fetched by the other. Bounded, evicts the
    * oldest entry once full.
    */
  private val blockCache = new BoundedBlockCache(virtualMempoolHeight * 4)

  @volatile var currentHeight = Env.conf.getInt("syncStart")
  @volatile var syncing = true

  /** Full height of the node as of the last getNodeInfo call, for /health and /ready. */
  @volatile var lastNodeHeight: Int = 0

  /** Whether the most recent (or in-progress) startup resumed from a persisted
    * checkpoint (Paideia.restoreState) instead of falling back to a full archive
    * replay, and the height that checkpoint was taken at. Set once by
    * initializeFromActor() before any sync activity; read by HealthController.
    */
  @volatile var restoredFromCheckpoint: Boolean = false
  @volatile var checkpointHeight: Option[Int] = None

  /** How often (in blocks) to checkpoint state while still far behind the chain tip;
    * once within virtualMempoolHeight+1 of the tip, every block is checkpointed
    * regardless of this value (see CheckpointPolicy.shouldCheckpoint).
    */
  val checkpointInterval: Int =
    if (Env.conf.hasPath("checkpointInterval"))
      Env.conf.getInt("checkpointInterval")
    else 100

  /** Guards the Initialize ask so it only ever runs on the very first pass through the
    * scheduled task's body, not on every retry after a caught exception restarts it
    * (currentHeight/syncing already reflect prior progress on a retry, so re-running
    * Initialize would be both wrong - it would try to restore into a non-empty
    * registry - and redundant).
    */
  private val initializedOnce = new java.util.concurrent.atomic.AtomicBoolean(false)

  private def fetchNodeHeight(datasource: NodeAndExplorerDataSourceImpl): Int = {
    val h = nodeCall[org.ergoplatform.restapi.client.NodeInfo]("getNodeInfo")(
      datasource.getNodeInfoApi().getNodeInfo()
    ).getFullHeight()
    lastNodeHeight = h
    h
  }
  /** Sends Initialize to the state actor and applies the result. Must be called
    * exactly once, before any archive replay or block sync. restoreState can take a
    * while for a large state (rebuilding every registry and verifying every digest
    * against disk), so this uses a generous timeout of its own rather than the
    * class-level 25s `timeout` used for per-transaction asks.
    */
  private def initializeFromActor(): Unit = {
    val restored = Await.result(
      (paideiaActor ? Initialize)(Timeout(120.seconds)).mapTo[Option[Int]],
      120.seconds
    )
    restored match {
      case Some(height) =>
        restoredFromCheckpoint = true
        checkpointHeight = Some(height)
        // persistState is only ever called after the block at `height` has been
        // fully handled (commit+checkpoint happens right before currentHeight is
        // advanced past it in syncRemainingBlocks/syncFromArchive), so the next
        // block sync must handle is height + 1.
        currentHeight = height + 1
        logger.info(
          s"""Sync resuming at height ${currentHeight.toString} after restoring checkpoint at ${height.toString}"""
        )
      case None =>
        restoredFromCheckpoint = false
        checkpointHeight = None
        logger.info(
          s"""Sync starting from configured syncStart (${currentHeight.toString}); full archive replay required"""
        )
    }
  }

  /** Requests a checkpoint (commit + persistState) at `height`. Best-effort: a failed
    * or slow checkpoint is logged but never aborts the sync loop - the transaction
    * archive remains the source of truth for a replay if persistence never catches up.
    */
  private def checkpoint(height: Int): Unit = {
    Try(
      Await.result(
        (paideiaActor ? CommitBlock(height))(Timeout(120.seconds)).mapTo[Try[Unit]],
        120.seconds
      )
    ) match {
      case Success(Success(_)) =>
        logger.info(s"""Checkpointed state at height ${height.toString}""")
      case Success(Failure(e)) =>
        logger.error(
          s"""Failed to persist checkpoint at height ${height.toString}: ${e.getMessage()}""",
          e
        )
      case Failure(e) =>
        logger.error(
          s"""Checkpoint request at height ${height.toString} timed out or failed: ${e.getMessage()}""",
          e
        )
    }
  }

  var mempoolTransactions = mutable.HashMap[String, ErgoTransaction]()

  /** Mempool transactions already rolled back as orphans. They may linger in the node's
    * mempool for a while; they must be neither re-rolled-back nor, once evicted, rolled back
    * a second time.
    */
  val orphanedMempoolTxs = mutable.HashSet[String]()

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

      if (initializedOnce.compareAndSet(false, true)) {
        initializeFromActor()
      }

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
      try {
        val socket = zContext.createSocket(SocketType.SUB)
        val zeroMQIP = Env.conf.getString("zmqHost")
        val zeroMQPort = Env.conf.getString("zmqPort")
        socket.connect(f"tcp://${zeroMQIP}:${zeroMQPort}")
        socket.subscribe("mempool")
        socket.subscribe("newBlock")
        socket.setReceiveTimeOut(180000)

        generateTransactions(ergoClient)

        var consecutiveTimeouts = 0

        while (true) {
          val message = socket.recvStr()
          if (message == null) {
            consecutiveTimeouts += 1
            logger.info(
              "No ZMQ message for 3 minutes, running fallback poll"
            )
            try {
              syncRemainingBlocks(ergoClient)
              syncMempool(ergoClient)
              generateTransactions(ergoClient)
            } catch {
              case e: Exception =>
                logger.error(e.getStackTrace().map(_.toString()).mkString)
                logger.error(e.getMessage(), e)
            }
            if (consecutiveTimeouts >= 10) {
              throw new RuntimeException(
                "ZMQ subscription appears dead, restarting sync task"
              )
            }
          } else {
            consecutiveTimeouts = 0
            try {
              if (message == "newBlock") {
                logger.info("New block")
                val blockHeader = socket.recvStr()
                if (blockHeader == null) {
                  logger.warn(
                    "ZMQ payload frame for newBlock was null, skipping"
                  )
                } else {
                  syncRemainingBlocks(ergoClient)
                  syncMempool(ergoClient)
                  generateTransactions(ergoClient)
                }
              }
              if (message == "mempool") {
                logger.info("New mempool transaction")
                val transactionId = socket.recvStr()
                if (transactionId == null) {
                  logger.warn(
                    "ZMQ payload frame for mempool was null, skipping"
                  )
                } else {
                  if (syncMempoolTransaction(ergoClient, transactionId))
                    generateTransactions(ergoClient)
                }
              }
            } catch {
              case e: Exception =>
                logger.error(e.getStackTrace().map(_.toString()).mkString)
                logger.error(e.getMessage(), e)
            }
          }
        }
      } finally {
        zContext.close()
      }

    } catch {
      case e: Exception => {
        logger.error(e.getStackTrace().map(_.toString()).mkString)
        logger.error(e.getMessage(), e)
      }
    }
  )

  private def nodeCall[T](
      desc: String,
      maxAttempts: Int = 5,
      valid: T => Boolean = (_: T) => true
  )(call: => retrofit2.Call[T]): T = {
    var attempt = 1
    var lastException: Option[Exception] = None
    var lastErrorMessage: String = "unknown error"
    while (attempt <= maxAttempts) {
      try {
        val resp = call.execute()
        if (resp.isSuccessful() && resp.body() != null && valid(resp.body())) {
          return resp.body()
        } else {
          lastException = None
          lastErrorMessage =
            if (!resp.isSuccessful())
              s"HTTP ${resp.code()}: ${resp.message()}"
            else if (resp.body() == null)
              s"HTTP ${resp.code()}: empty body"
            else
              s"HTTP ${resp.code()}: invalid body"
        }
      } catch {
        case e: Exception =>
          lastException = Some(e)
          lastErrorMessage = e.getMessage()
      }
      logger.warn(
        s"Node call '$desc' failed (attempt $attempt/$maxAttempts): $lastErrorMessage"
      )
      if (attempt < maxAttempts) {
        val backoffSeconds = math.min(8, math.pow(2, attempt - 1).toInt)
        Thread.sleep(backoffSeconds * 1000L)
      }
      attempt += 1
    }
    lastException match {
      case Some(e) =>
        throw new RuntimeException(
          s"Node call failed after $maxAttempts attempts: $desc: $lastErrorMessage",
          e
        )
      case None =>
        throw new RuntimeException(
          s"Node call failed after $maxAttempts attempts: $desc: $lastErrorMessage"
        )
    }
  }

  /** Small bounded cache of headerId -> FullBlock, evicting the oldest entry once
    * full. Written from prefetch-pool threads and read from the sync thread, so all
    * access is synchronized.
    */
  private class BoundedBlockCache(maxSize: Int) {
    private val entries =
      new java.util.LinkedHashMap[String, org.ergoplatform.restapi.client.FullBlock]()

    def put(headerId: String, block: org.ergoplatform.restapi.client.FullBlock): Unit =
      synchronized {
        entries.put(headerId, block)
        if (entries.size() > maxSize) {
          val it = entries.entrySet().iterator()
          if (it.hasNext()) {
            it.next()
            it.remove()
          }
        }
      }

    def get(headerId: String): Option[org.ergoplatform.restapi.client.FullBlock] =
      synchronized(Option(entries.get(headerId)))
  }

  /** Bounded lookahead window for syncRemainingBlocks: while height `h` is being
    * processed, fetches for h+1 .. h+blockPrefetch run concurrently on the
    * prefetch pool. Only ever accessed from the single sync-loop thread, so the
    * window map itself needs no synchronization; fetched blocks are also fed into
    * the shared BoundedBlockCache so syncMempool can reuse them.
    */
  private class BlockPrefetcher(
      datasource: NodeAndExplorerDataSourceImpl,
      cache: BoundedBlockCache
  ) {
    private val window =
      mutable.Map.empty[Int, Future[org.ergoplatform.restapi.client.FullBlock]]

    private def fetchBlock(height: Int): org.ergoplatform.restapi.client.FullBlock = {
      val blockHeaderId = nodeCall(
        s"getFullBlockAt($height)",
        valid = (l: java.util.List[String]) => !l.isEmpty
      )(
        datasource.getNodeBlocksApi().getFullBlockAt(height)
      ).get(0)
      val fullBlock =
        nodeCall[org.ergoplatform.restapi.client.FullBlock]("getFullBlockById")(
          datasource.getNodeBlocksApi().getFullBlockById(blockHeaderId)
        )
      cache.put(blockHeaderId, fullBlock)
      fullBlock
    }

    def ensurePrefetched(fromHeight: Int, upToHeightInclusive: Int): Unit =
      (fromHeight to upToHeightInclusive).foreach { h =>
        if (!window.contains(h)) {
          window(h) = Future(fetchBlock(h))(prefetchEc)
        }
      }

    def takeBlock(height: Int): org.ergoplatform.restapi.client.FullBlock =
      window.remove(height) match {
        case Some(fut) => Await.result(fut, 120.seconds)
        case None      => fetchBlock(height)
      }

    def clear(): Unit = window.clear()
  }

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
                              case e: Exception if TxRejection.isLostRace(
                                    e.getMessage()
                                  ) =>
                                logger.info(
                                  s"""Lost race submitting transaction type: ${ut
                                      .getClass()
                                      .getCanonicalName()}: ${e.getMessage()}"""
                                )
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
            fetchNodeHeight(datasource)

          var virtualCurrentHeight = currentHeight

          while (virtualCurrentHeight <= nodeHeight) {
            val blockHeaderId = nodeCall(
              s"getFullBlockAt($virtualCurrentHeight)",
              valid = (l: java.util.List[String]) => !l.isEmpty
            )(
              datasource.getNodeBlocksApi().getFullBlockAt(virtualCurrentHeight)
            ).get(0);
            val fullBlock = blockCache.get(blockHeaderId).getOrElse {
              val fetched =
                nodeCall[org.ergoplatform.restapi.client.FullBlock]("getFullBlockById")(
                  datasource.getNodeBlocksApi().getFullBlockById(blockHeaderId)
                )
              blockCache.put(blockHeaderId, fetched)
              fetched
            }
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
              nodeHeight = fetchNodeHeight(datasource)
          }

          while (limit == resultSize) {
            val memTransactions =
              nodeCall[org.ergoplatform.restapi.client.Transactions]("getUnconfirmedTransactions")(
                datasource
                  .getNodeTransactionsApi()
                  .getUnconfirmedTransactions(limit, offset)
              )
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
              if (orphanedMempoolTxs.remove(kv._1)) {
                logger.info(
                  s"""Orphan transaction left mempool (already rolled back): ${kv._1}"""
                )
              } else {
              // TODO only rollback transactions that are relevant (paideiaeventresponse.status >= 1)
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
            }
          )

          mempoolTransactions = newMempoolTransactions

          val orphanedTxs = mutable.Buffer[String]()
          val orphanedOutputs = mutable.Buffer[String]()
          orphanedOutputs ++= orphanedMempoolTxs.toSeq
            .flatMap(mempoolTransactions.get)
            .flatMap(_.getOutputs().asScala.map(_.getBoxId()))
          var foundNewOrphan = true

          while (foundNewOrphan) {
            foundNewOrphan = false
            mempoolTransactions.foreach(kv => {
              if (!orphanedTxs.contains(kv._1) && !orphanedMempoolTxs.contains(kv._1))
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

          orphanedMempoolTxs ++= orphanedTxs
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
            fetchNodeHeight(datasource)

          logger.info(s"""Node height: ${nodeHeight
              .toString()} Current height: ${currentHeight.toString()}""")

          val prefetcher = new BlockPrefetcher(datasource, blockCache)
          try {
            while (currentHeight < (nodeHeight - virtualMempoolHeight)) {
              val prefetchBound = math.min(
                currentHeight + blockPrefetch,
                nodeHeight - virtualMempoolHeight - 1
              )
              prefetcher.ensurePrefetched(currentHeight, prefetchBound)
              val fullBlock = prefetcher.takeBlock(currentHeight)
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
              if (
                CheckpointPolicy.shouldCheckpoint(
                  currentHeight,
                  nodeHeight,
                  virtualMempoolHeight,
                  checkpointInterval
                )
              )
                checkpoint(currentHeight)
              currentHeight += 1
              if (currentHeight >= (nodeHeight - virtualMempoolHeight))
                nodeHeight = fetchNodeHeight(datasource)
              if (currentHeight % 100 == 0)
                logger.info(
                  s"""Syncer current height: ${currentHeight.toString}"""
                )
            }
          } finally {
            prefetcher.clear()
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

          // Leave a checkpoint behind so a fresh replay doesn't have to be redone in
          // full on the very next restart; currentHeight is set above to (last
          // archived file's height) + 1, i.e. the height of the last block actually
          // handled by this loop.
          if (archivedTransactionFiles.nonEmpty) {
            checkpoint(currentHeight - 1)
          }
        }
      }
    )
}
