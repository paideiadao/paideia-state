package controllers

import javax.inject._
import play.api.mvc.{BaseController, ControllerComponents}
import play.api.libs.json.{JsObject, Json}
import tasks.PaideiaSyncTask

@Singleton
class HealthController @Inject() (
    val controllerComponents: ControllerComponents,
    syncTask: PaideiaSyncTask
) extends BaseController {

  private def syncStatus: JsObject = {
    val nodeHeight = syncTask.lastNodeHeight
    val currentHeight = syncTask.currentHeight
    Json.obj(
      "syncing" -> syncTask.syncing,
      "currentHeight" -> currentHeight,
      "nodeHeight" -> nodeHeight,
      "lag" -> (if (nodeHeight > 0) nodeHeight - currentHeight else -1)
    )
  }

  /** Liveness: always 200 while the process is up; carries sync status for inspection. */
  def health = Action {
    Ok(Json.obj("status" -> "ok") ++ syncStatus)
  }

  /** Readiness: 503 while syncing (every other endpoint rejects requests then), 200 once
    * caught up. Point load balancers and health checks here.
    */
  def ready = Action {
    if (syncTask.syncing) ServiceUnavailable(Json.obj("status" -> "syncing") ++ syncStatus)
    else Ok(Json.obj("status" -> "ready") ++ syncStatus)
  }
}
