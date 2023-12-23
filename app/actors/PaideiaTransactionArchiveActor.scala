package actors

import akka.actor.Props
import akka.actor.Actor
import play.api.Logging
import scala.collection.mutable.HashMap
import play.api.libs.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import org.ergoplatform.restapi.client.ErgoTransaction
import com.google.gson.Gson
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption

object PaideiaTransactionArchiveActor {
  def props = Props[PaideiaTransactionArchiveActor]
}

class PaideiaTransactionArchiveActor extends Actor with Logging {
  import PaideiaTransactionArchiveActor._
  import im.paideia.common.events.TransactionEvent

  var transactions: HashMap[Int, Array[ErgoTransaction]] =
    new HashMap[Int, Array[ErgoTransaction]]()

  def receive = { case te: TransactionEvent =>
    archive(te)
  }

  def archive(te: TransactionEvent): Unit = {
    if (!transactions.contains(te.height)) {
      var arr = Array[ErgoTransaction]()
      transactions.put(te.height, arr)
    }

    transactions.update(te.height, transactions.get(te.height).get :+ te.tx)

    transactions.keys.foreach((h: Int) => {
      if (h < te.height - 30) {
        writeEvents(h, transactions.get(h).get)
      }
    })
    transactions = transactions.filter((kv: (Int, Array[ErgoTransaction])) =>
      kv._1 >= te.height - 30
    )
  }

  def writeEvents(height: Int, et: Array[ErgoTransaction]) = {
    val json = new Gson().toJson(et)
    Files.write(
      Paths.get("transaction_archive/" + height.toString()),
      json
        .toString()
        .getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE
    )
  }
}
