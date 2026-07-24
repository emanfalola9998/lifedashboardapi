package controllers

import play.api.mvc.*
import play.api.libs.json.*
import services.GoogleCalendarService
import javax.inject.*
import scala.concurrent.ExecutionContext

class GoogleAuthController @Inject()(
  cc: ControllerComponents,
  calendarService: GoogleCalendarService,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  def redirect: Action[AnyContent] = Action { implicit request =>
    val userId = request.getQueryString("userId").getOrElse("default-user")
    Redirect(calendarService.getAuthUrl(userId))
  }

  def callback: Action[AnyContent] = Action.async { implicit request =>
    val code   = request.getQueryString("code").getOrElse("")
    val userId = request.getQueryString("state").getOrElse("default-user")

    calendarService.exchangeCode(code, userId).map { _ =>
      Redirect("http://localhost:3000?calendar=connected")
    }.recover { case ex =>
      InternalServerError(s"OAuth failed: ${ex.getMessage}")
    }
  }

  def status(userId: String): Action[AnyContent] = Action {
    val connected = calendarService.isConnected(userId)
    Ok(Json.obj("connected" -> connected))
  }

  def events(userId: String): Action[AnyContent] = Action.async {
    calendarService.getEvents(userId).map(data => Ok(data))
  }

  def disconnect(userId: String): Action[AnyContent] = Action {
    calendarService.disconnect(userId)
    Ok(Json.obj("disconnected" -> true))
  }
}
