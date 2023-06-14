package models

import play.api.libs.json._

trait Action

object Action {
  implicit val format = Format[Action](
    Reads { js =>
      // use the fruitType field to determine how to deserialize
      val actionType = (JsPath \ "actionType").read[String].reads(js)
      actionType.fold(
        errors => JsError("actionType undefined or incorrect"),
        { case "SendFundsAction" =>
          (JsPath \ "action").read[SendFundsAction].reads(js)
        }
      )
    },
    Writes { case b: SendFundsAction =>
      JsObject(
        Seq(
          "actionType" -> JsString("SendFundsAction"),
          "action" -> SendFundsAction.format.writes(b)
        )
      )
    }
  )
}
