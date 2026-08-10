package controllers

import play.api.mvc.*
import play.api.libs.json.*
import play.api.libs.ws.*
import repositories.Trading212Repository
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Trading212Controller @Inject()(
  cc: ControllerComponents,
  ws: WSClient,
  repo: Trading212Repository,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  private val T212_BASE = "https://live.trading212.com/api/v0"

  def status(userId: String): Action[AnyContent] = Action {
    Ok(Json.obj("connected" -> repo.getKey(userId).isDefined))
  }

  def connect(userId: String): Action[JsValue] = Action(parse.json) { request =>
    (request.body \ "apiKey").asOpt[String].filter(_.nonEmpty) match {
      case Some(key) =>
        repo.saveKey(userId, key)
        Ok(Json.obj("connected" -> true))
      case None =>
        BadRequest(Json.obj("error" -> "apiKey is required"))
    }
  }

  def disconnect(userId: String): Action[AnyContent] = Action {
    repo.deleteKey(userId)
    Ok(Json.obj("connected" -> false))
  }

  def portfolio(userId: String): Action[AnyContent] = Action.async {
    repo.getKey(userId) match {
      case None => Future.successful(Unauthorized(Json.obj("error" -> "Not connected")))
      case Some(apiKey) =>
        val headers = Seq("Authorization" -> apiKey)

        val positionsFut = ws.url(s"$T212_BASE/equity/portfolio")
          .withHttpHeaders(headers: _*)
          .get()

        val cashFut = ws.url(s"$T212_BASE/equity/account/cash")
          .withHttpHeaders(headers: _*)
          .get()

        for {
          posRes  <- positionsFut
          cashRes <- cashFut
        } yield {
          if (posRes.status == 401 || cashRes.status == 401) {
            Unauthorized(Json.obj("error" -> "Invalid API key"))
          } else if (posRes.status != 200) {
            BadGateway(Json.obj("error" -> s"Trading 212 error: ${posRes.status}"))
          } else {
            val positions = posRes.json.as[JsArray].value.map { pos =>
              val ticker      = (pos \ "ticker").asOpt[String].getOrElse("")
              val quantity    = (pos \ "quantity").asOpt[Double].getOrElse(0.0)
              val avgPrice    = (pos \ "averagePrice").asOpt[Double].getOrElse(0.0)
              val currentPrice = (pos \ "currentPrice").asOpt[Double].getOrElse(0.0)
              val ppl         = (pos \ "ppl").asOpt[Double].getOrElse(0.0)
              val value       = quantity * currentPrice
              val pct         = if (avgPrice > 0) ((currentPrice - avgPrice) / avgPrice) * 100 else 0.0

              Json.obj(
                "ticker"        -> ticker,
                "quantity"      -> quantity,
                "averagePrice"  -> avgPrice,
                "currentPrice"  -> currentPrice,
                "value"         -> value,
                "ppl"           -> ppl,
                "pctChange"     -> pct,
              )
            }

            val cash = cashRes.json
            Ok(Json.obj(
              "positions" -> JsArray(positions),
              "cash"      -> Json.obj(
                "free"     -> (cash \ "free").asOpt[Double].getOrElse(0.0),
                "invested" -> (cash \ "invested").asOpt[Double].getOrElse(0.0),
                "ppl"      -> (cash \ "ppl").asOpt[Double].getOrElse(0.0),
                "total"    -> (cash \ "total").asOpt[Double].getOrElse(0.0),
              )
            ))
          }
        }
    }
  }
}
