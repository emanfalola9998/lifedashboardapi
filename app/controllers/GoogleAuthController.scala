package controllers

import play.api.mvc.*
import play.api.libs.json.*
import services.GoogleCalendarService
import actions.SecuredAction
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.TimeoutException

class GoogleAuthController @Inject()(
  cc: ControllerComponents,
  calendarService: GoogleCalendarService,
  config: play.api.Configuration,
  secured: SecuredAction,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  private val frontendUrl = config.get[String]("google.frontendUrl")

  def redirect: Action[AnyContent] = Action { implicit request =>
    val userId = request.getQueryString("userId").getOrElse("default-user")
    Redirect(calendarService.getAuthUrl(userId))
  }

  def callback: Action[AnyContent] = Action.async { implicit request =>
    val code   = request.getQueryString("code").getOrElse("")
    val userId = request.getQueryString("state").getOrElse("default-user")

    calendarService.exchangeCode(code, userId).map { _ =>
      Redirect(s"$frontendUrl?calendar=connected")
    }.recover {
      case _: TimeoutException => GatewayTimeout(Json.obj("error" -> "Google OAuth timed out"))
      case ex                  => InternalServerError(s"OAuth failed: ${ex.getMessage}")
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
