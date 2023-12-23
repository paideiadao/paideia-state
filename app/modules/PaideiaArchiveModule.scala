package modules

import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

import actors.PaideiaTransactionArchiveActor

class PaideiaArchiveModule extends AbstractModule with AkkaGuiceSupport {
  override def configure = {
    bindActor[PaideiaTransactionArchiveActor]("paideia-archive")
  }
}
