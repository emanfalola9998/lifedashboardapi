package controllers

import play.api.mvc.*
import play.api.libs.json.*
import services.GoogleCalendarService
import actions.SecuredAction
import javax.inject.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.TimeoutException
import java.util.Base64

class GoogleAuthController @Inject()(
  cc: ControllerComponents,
  calendarService: GoogleCalendarService,
  config: play.api.Configuration,
  secured: SecuredAction,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  private val frontendUrl  = config.get[String]("google.frontendUrl")
  private val appSecret    = config.get[String]("play.http.secret.key")
  private val StateTtlMs   = 10 * 60 * 1000L // 10 minutes

  private def hmac(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(appSecret.getBytes("UTF-8"), "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(data.getBytes("UTF-8")))
  }

  private def signState(userId: String): String = {
    val expiry  = System.currentTimeMillis() + StateTtlMs
    val payload = s"$userId|$expiry"
    s"$payload|${hmac(payload)}"
  }

  private def verifyState(state: String): Option[String] = {
    state.split('|') match {
      case Array(userId, expiry, sig) =>
        val payload = s"$userId|$expiry"
        val ok = hmac(payload) == sig && expiry.toLongOption.exists(_ > System.currentTimeMillis())
        if (ok) Some(userId) else None
      case _ => None
    }
  }

  def redirect: Action[AnyContent] = secured { implicit request =>
    val userId = request.getQueryString("userId").getOrElse("default-user")
    Redirect(calendarService.getAuthUrl(userId, signState(userId)))
  }

  def callback: Action[AnyContent] = Action.async { implicit request =>
    val code      = request.getQueryString("code").getOrElse("")
    val rawState  = request.getQueryString("state").getOrElse("")
    verifyState(rawState) match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Invalid or expired OAuth state")))
      case Some(userId) =>
        calendarService.exchangeCode(code, userId).map { _ =>
          Redirect(s"$frontendUrl?calendar=connected")
        }.recover {
          case _: TimeoutException => GatewayTimeout(Json.obj("error" -> "Google OAuth timed out"))
          case _                   => InternalServerError(Json.obj("error" -> "OAuth failed"))
        }
    }
  }

  def status(userId: String): Action[AnyContent] = secured {
    Ok(Json.obj("connected" -> calendarService.isConnected(userId)))
  }

  def events(userId: String): Action[AnyContent] = secured.async {
    calendarService.getEvents(userId).map(data => Ok(data)).recover {
      case _: TimeoutException => GatewayTimeout(Json.obj("error" -> "Google Calendar timed out"))
    }
  }

  def disconnect(userId: String): Action[AnyContent] = secured {
    calendarService.disconnect(userId)
    Ok(Json.obj("disconnected" -> true))
  }
}
