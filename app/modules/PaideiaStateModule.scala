package modules

import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

import actors.PaideiaStateActor

class PaideiaStateModule extends AbstractModule with AkkaGuiceSupport {
  override def configure = {
    bindActor[PaideiaStateActor]("paideia-state")
  }
}
