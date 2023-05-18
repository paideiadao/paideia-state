package modules

import play.api.inject._
import tasks.PaideiaSyncTask

class PaideiaSyncModule
    extends SimpleModule(bind[PaideiaSyncTask].toSelf.eagerly())
