package services

import repositories.{GoogleToken, GoogleTokenRepository}
import play.api.libs.ws.WSClient
import play.api.libs.ws.writeableOf_urlEncodedForm
import play.api.Configuration
import play.api.libs.json.*
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GoogleCalendarService @Inject()(
  ws: WSClient,
  config: Configuration,
  tokenRepo: GoogleTokenRepository,
  ec: ExecutionContext
) {

  given ExecutionContext = ec

  private val clientId    = config.get[String]("google.clientId")
  private val clientSecret = config.get[String]("google.clientSecret")
  private val redirectUri  = config.get[String]("google.redirectUri")
  private val frontendUrl  = config.get[String]("google.frontendUrl")

  def getAuthUrl(userId: String): String = {
    def encode(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
    val params = Seq(
      s"client_id=${encode(clientId)}",
      s"redirect_uri=${encode(redirectUri)}",
      s"response_type=code",
      s"scope=${encode("https://www.googleapis.com/auth/calendar.readonly")}",
      s"access_type=offline",
      s"prompt=consent",
      s"state=${encode(userId)}"
    ).mkString("&")
    s"https://accounts.google.com/o/oauth2/v2/auth?$params"
  }

  def exchangeCode(code: String, userId: String): Future[Unit] = {
    ws.url("https://oauth2.googleapis.com/token")
      .post(Map(
        "code"          -> Seq(code),
        "client_id"     -> Seq(clientId),
        "client_secret" -> Seq(clientSecret),
        "redirect_uri"  -> Seq(redirectUri),
        "grant_type"    -> Seq("authorization_code")
      ))
      .map { response =>
        val json         = response.json
        val accessToken  = (json \ "access_token").as[String]
        val refreshToken = (json \ "refresh_token").asOpt[String]
        val expiresIn    = (json \ "expires_in").as[Int]
        val expiresAt    = System.currentTimeMillis() + (expiresIn * 1000L)
        tokenRepo.upsertToken(GoogleToken(userId, accessToken, refreshToken, expiresAt))
      }
  }

  private def refreshIfNeeded(token: GoogleToken): Future[GoogleToken] = {
    val bufferMs = 60 * 1000L
    if (System.currentTimeMillis() < token.expiresAt - bufferMs) {
      Future.successful(token)
    } else {
      token.refreshToken match {
        case None => Future.failed(new Exception("No refresh token — user must re-authenticate"))
        case Some(rt) =>
          ws.url("https://oauth2.googleapis.com/token")
            .post(Map(
              "refresh_token" -> Seq(rt),
              "client_id"     -> Seq(clientId),
              "client_secret" -> Seq(clientSecret),
              "grant_type"    -> Seq("refresh_token")
            ))
            .map { response =>
              val json           = response.json
              val newAccessToken = (json \ "access_token").as[String]
              val expiresIn      = (json \ "expires_in").as[Int]
              val expiresAt      = System.currentTimeMillis() + (expiresIn * 1000L)
              val refreshed      = token.copy(accessToken = newAccessToken, expiresAt = expiresAt)
              tokenRepo.upsertToken(refreshed)
              refreshed
            }
      }
    }
  }

  def getEvents(userId: String): Future[JsValue] = {
    tokenRepo.getToken(userId) match {
      case None => Future.successful(Json.obj("connected" -> false, "events" -> JsArray()))
      case Some(token) =>
        refreshIfNeeded(token).flatMap { validToken =>
          val now      = java.time.Instant.now().toString
          val twoWeeks = java.time.Instant.now().plusSeconds(14 * 24 * 3600).toString
          ws.url("https://www.googleapis.com/calendar/v3/calendars/primary/events")
            .addQueryStringParameters(
              "timeMin"      -> now,
              "timeMax"      -> twoWeeks,
              "singleEvents" -> "true",
              "orderBy"      -> "startTime",
              "maxResults"   -> "20"
            )
            .addHttpHeaders("Authorization" -> s"Bearer ${validToken.accessToken}")
            .get()
            .map { response =>
              val items = (response.json \ "items").getOrElse(JsArray())
              Json.obj("connected" -> true, "events" -> items)
            }
        }.recover { case _ =>
          Json.obj("connected" -> false, "events" -> JsArray())
        }
    }
  }

  def isConnected(userId: String): Boolean = tokenRepo.getToken(userId).isDefined

  def disconnect(userId: String): Unit = tokenRepo.deleteToken(userId)
}
