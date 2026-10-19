package parsing

import play.api.libs.json.*

object T212Normaliser {

  case class Position(
    ticker: String,
    quantity: Double,
    averagePrice: Double,
    currentPrice: Double,
    value: Double,
    ppl: Double,
    pctChange: Double
  )

  /** Accepts either a raw JsArray or {"items":[...]} — T212 returns both depending on account type. */
  def normalise(json: JsValue): Seq[Position] = {
    val items: Seq[JsValue] = json match {
      case arr: JsArray  => arr.value.toSeq
      case obj: JsObject => (obj \ "items").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty)
      case _             => Seq.empty
    }
    items.map { pos =>
      val ticker       = (pos \ "ticker").asOpt[String].getOrElse("")
      val quantity     = (pos \ "quantity").asOpt[Double].getOrElse(0.0)
      val avgPrice     = (pos \ "averagePrice").asOpt[Double].getOrElse(0.0)
      val currentPrice = (pos \ "currentPrice").asOpt[Double].getOrElse(0.0)
      val ppl          = (pos \ "ppl").asOpt[Double].getOrElse(0.0)
      val value        = quantity * currentPrice
      val pct          = if (avgPrice > 0) ((currentPrice - avgPrice) / avgPrice) * 100 else 0.0
      Position(ticker, quantity, avgPrice, currentPrice, value, ppl, pct)
    }
  }
}
