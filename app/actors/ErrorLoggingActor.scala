package actors

import akka.actor.Actor
import play.api.Logging
import akka.actor.Props
import scorex.util.encode.Base16
import org.bouncycastle.jcajce.provider.digest.Blake2b.Blake2b256
import reflect.io._
import Path._

object ErrorLoggingActor {
  def props = Props[ErrorLoggingActor]
}

class ErrorLoggingActor extends Actor with Logging {

  override def receive: Receive = { case e: Exception => handleException(e) }

  def handleException(e: Exception): Unit = {
    try {
      val hasher = new Blake2b256()
      hasher.update(
        (
          e.getClass().toString() ++ e
            .getStackTrace()
            .filter(_.toString().startsWith("im.paideia"))(0)
            .toString
        ).getBytes()
      )
      val exceptionSignature = Base16.encode(hasher.digest())
      val existingErrorFiles =
        "errors".toDirectory.files
          .filter(_.path.contains(exceptionSignature))
          .toArray
      if (existingErrorFiles.length > 0) {
        existingErrorFiles.foreach(f => {
          val nameSplit = f.name.split("-")
          val repeatCount = nameSplit(0).toInt + 1
          val newFile = File(
            Path(
              f.path.replace(
                f.name,
                repeatCount.toString ++ "-" ++ nameSplit
                  .slice(1, nameSplit.size)
                  .mkString("-")
              )
            )
          )
          if (!f.jfile.renameTo(newFile.jfile))
            logger.info(f"Failed renaming to ${newFile.path}")
        })
      } else {
        val newErrorFile = File(
          (Path(
            "errors/" ++ "1-" ++ exceptionSignature ++ "-" ++ e.getClass.getCanonicalName.toString ++ ".log"
          ))
        )
        newErrorFile.writeAll(
          (Seq(e.getMessage()) ++ e.getStackTrace().map(_.toString()))
            .mkString("\n")
        )
      }
      logger.error(f"Logged error ${e.getClass().getCanonicalName()}")
    } catch {
      case e: Exception => logger.error(e.getMessage(), e)
    }
  }

}
