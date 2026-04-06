package controllers

import javax.inject._
import play.api.mvc.{BaseController, ControllerComponents}
import play.api.libs.json.Json

@Singleton
class HealthController @Inject() (
    val controllerComponents: ControllerComponents
) extends BaseController {

  def health = Action {
    Ok(Json.obj("status" -> "ok"))
  }
}
