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

  private val LIVE_BASE = "https://live.trading212.com/api/v0"
  private val DEMO_BASE = "https://demo.trading212.com/api/v0"

  def status(userId: String): Action[AnyContent] = Action {
    Ok(Json.obj("connected" -> repo.getKey(userId).isDefined))
  }

  def connect(userId: String): Action[JsValue] = Action.async(parse.json) { request =>
    (request.body \ "apiKey").asOpt[String].map(_.trim).filter(_.nonEmpty) match {
      case None => Future.successful(BadRequest(Json.obj("error" -> "apiKey is required")))
      case Some(key) =>
        val apiId  = (request.body \ "apiId").asOpt[String].map(_.trim).getOrElse("")
        // Build Basic auth: base64(id:key). If no id provided fall back to raw key for legacy.
        val authHeader = if (apiId.nonEmpty) {
          val encoded = java.util.Base64.getEncoder.encodeToString(s"$apiId:$key".getBytes("UTF-8"))
          s"Basic $encoded"
        } else key

        // Probe live first, fall back to demo — stores "live:<authHeader>" or "demo:<authHeader>"
        ws.url(s"$LIVE_BASE/equity/account/cash").withHttpHeaders("Authorization" -> authHeader).get().flatMap { res =>
          if (res.status == 200) {
            repo.saveKey(userId, s"live:$authHeader")
            Future.successful(Ok(Json.obj("connected" -> true, "mode" -> "live")))
          } else {
            ws.url(s"$DEMO_BASE/equity/account/cash").withHttpHeaders("Authorization" -> authHeader).get().map { dRes =>
              if (dRes.status == 200) {
                repo.saveKey(userId, s"demo:$authHeader")
                Ok(Json.obj("connected" -> true, "mode" -> "demo"))
              } else {
                Unauthorized(Json.obj("error" -> s"Invalid credentials (got ${res.status} from live, ${dRes.status} from demo) — check your ID and Key"))
              }
            }
          }
        }
    }
  }

  def disconnect(userId: String): Action[AnyContent] = Action {
    repo.deleteKey(userId)
    Ok(Json.obj("connected" -> false))
  }

  def portfolio(userId: String): Action[AnyContent] = Action.async {
    repo.getKey(userId) match {
      case None => Future.successful(Unauthorized(Json.obj("error" -> "Not connected")))
      case Some(stored) =>
        val (base, apiKey) = if (stored.startsWith("demo:")) (DEMO_BASE, stored.drop(5))
                             else (LIVE_BASE, if (stored.startsWith("live:")) stored.drop(5) else stored)
        val headers = Seq("Authorization" -> apiKey)

        val positionsFut = ws.url(s"$base/equity/portfolio")
          .withHttpHeaders(headers: _*)
          .get()

        val cashFut = ws.url(s"$base/equity/account/cash")
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
            // T212 returns either a raw array or {"items":[...]} depending on account type
            val rawItems: Seq[JsValue] = posRes.json match {
              case arr: JsArray => arr.value.toSeq
              case obj: JsObject => (obj \ "items").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty)
              case _ => Seq.empty
            }
            val positions = rawItems.map { pos =>
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
              "positions"    -> JsArray(positions),
              "rawPositions" -> posRes.json.toString(),
              "cash"         -> Json.obj(
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
