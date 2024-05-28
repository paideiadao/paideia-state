package modules

import actors.ErrorLoggingActor
import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

import actors.PaideiaTransactionArchiveActor

class ErrorLoggingModule extends AbstractModule with AkkaGuiceSupport {
  override def configure = {
    bindActor[ErrorLoggingActor]("error-logging")
  }
}
