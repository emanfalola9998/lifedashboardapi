package controllers

import play.api.mvc.*
import play.api.libs.json.*
import play.api.libs.ws.*
import repositories.Trading212Repository
import actions.SecuredAction
import parsing.T212Normaliser
import javax.inject.*
import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

@Singleton
class Trading212Controller @Inject()(
  cc: ControllerComponents,
  ws: WSClient,
  repo: Trading212Repository,
  secured: SecuredAction,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  private val LIVE_BASE = "https://live.trading212.com/api/v0"
  private val DEMO_BASE = "https://demo.trading212.com/api/v0"

  private def t212Get(base: String, path: String, auth: String): Future[WSResponse] =
    ws.url(s"$base$path")
      .withHttpHeaders("Authorization" -> auth)
      .withRequestTimeout(10.seconds)
      .get()

  def status(userId: String): Action[AnyContent] = secured.async {
    repo.hasKey(userId).map(connected => Ok(Json.obj("connected" -> connected)))
  }

  def connect(userId: String): Action[JsValue] = secured.async(parse.json) { (request: Request[JsValue]) =>
    (request.body \ "apiKey").asOpt[String].map(_.trim).filter(_.nonEmpty) match {
      case None => Future.successful(BadRequest(Json.obj("error" -> "apiKey is required")))
      case Some(key) =>
        val apiId  = (request.body \ "apiId").asOpt[String].map(_.trim).getOrElse("")
        // Build Basic auth: base64(id:key). If no id provided fall back to raw key for legacy.
        val authHeader = if (apiId.nonEmpty) {
          val encoded = java.util.Base64.getEncoder.encodeToString(s"$apiId:$key".getBytes("UTF-8"))
          s"Basic $encoded"
        } else key

        // Probe live first. Only a 401 (key not recognised there) means "try demo" — a 403, 429
        // or 5xx is reported as-is, so T212 being down isn't mistaken for bad credentials.
        t212Get(LIVE_BASE, "/equity/account/cash", authHeader).flatMap { live =>
          live.status match {
            case 200 => saveAndConfirm(userId, "live", authHeader)
            case 401 =>
              t212Get(DEMO_BASE, "/equity/account/cash", authHeader).flatMap { demo =>
                demo.status match {
                  case 200   => saveAndConfirm(userId, "demo", authHeader)
                  case 401   => Future.successful(Unauthorized(Json.obj("error" -> "Invalid credentials — check your API ID and key")))
                  case other => Future.successful(probeFailed(other))
                }
              }
            case other => Future.successful(probeFailed(other))
          }
        }.recover {
          case _: TimeoutException => GatewayTimeout(Json.obj("error" -> "Trading 212 timed out"))
        }
    }
  }

  private def saveAndConfirm(userId: String, mode: String, authHeader: String): Future[Result] =
    repo.saveKey(userId, s"$mode:$authHeader").map(_ => Ok(Json.obj("connected" -> true, "mode" -> mode)))

  private def probeFailed(status: Int): Result = status match {
    case 403   => BadRequest(Json.obj("error" -> "Trading 212 rejected this key — check its permissions and IP restrictions"))
    case 429   => TooManyRequests(Json.obj("error" -> "Trading 212 rate limit hit — try again in a minute"))
    case other => BadGateway(Json.obj("error" -> s"Trading 212 error: $other"))
  }

  def disconnect(userId: String): Action[AnyContent] = secured.async {
    repo.deleteKey(userId).map(_ => Ok(Json.obj("connected" -> false)))
  }

  def portfolio(userId: String): Action[AnyContent] = secured.async {
    repo.getKey(userId).flatMap {
      case None => Future.successful(Unauthorized(Json.obj("error" -> "Not connected")))
      case Some(stored) =>
        val (base, apiKey) = if (stored.startsWith("demo:")) (DEMO_BASE, stored.drop(5))
                             else (LIVE_BASE, if (stored.startsWith("live:")) stored.drop(5) else stored)

        // Both requests start here, before the for — so they run in parallel
        val positionsFut = t212Get(base, "/equity/portfolio", apiKey)
        val cashFut      = t212Get(base, "/equity/account/cash", apiKey)

        (for {
          posRes  <- positionsFut
          cashRes <- cashFut
        } yield {
          if (posRes.status == 401 || cashRes.status == 401) {
            Unauthorized(Json.obj("error" -> "Invalid API key"))
          } else if (posRes.status != 200) {
            BadGateway(Json.obj("error" -> s"Trading 212 error: ${posRes.status}"))
          } else {
            val positions = T212Normaliser.normalise(posRes.json).map { p =>
              Json.obj(
                "ticker"       -> p.ticker,
                "quantity"     -> p.quantity,
                "averagePrice" -> p.averagePrice,
                "currentPrice" -> p.currentPrice,
                "value"        -> p.value,
                "ppl"          -> p.ppl,
                "pctChange"    -> p.pctChange,
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
        }).recover {
          case _: TimeoutException =>
            GatewayTimeout(Json.obj("error" -> "Trading 212 timed out"))
          case _ =>
            BadGateway(Json.obj("error" -> "Trading 212 unavailable"))
        }
    }
  }
}
