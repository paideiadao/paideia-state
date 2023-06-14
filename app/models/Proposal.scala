package models

import play.api.libs.json._

trait Proposal

object Proposal {
  implicit val format = Format[Proposal](
    Reads { js =>
      // use the fruitType field to determine how to deserialize
      val proposalType = (JsPath \ "proposalType").read[String].reads(js)
      proposalType.fold(
        errors => JsError("proposalType undefined or incorrect"),
        { case "ProposalBasic" =>
          (JsPath \ "proposal").read[ProposalBasic].reads(js)
        }
      )
    },
    Writes { case b: ProposalBasic =>
      JsObject(
        Seq(
          "proposalType" -> JsString("ProposalBasic"),
          "proposal" -> ProposalBasic.format.writes(b)
        )
      )
    }
  )
}
