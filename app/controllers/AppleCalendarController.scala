package controllers

import play.api.mvc.*
import play.api.libs.json.*
import play.api.libs.ws.*
import actions.SecuredAction
import parsing.IcsParser
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppleCalendarController @Inject()(
  cc: ControllerComponents,
  ws: WSClient,
  secured: SecuredAction,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  private val ICloudPattern  = """^p\d+-caldav\.icloud\.com$""".r
  private val AllowedHosts   = Set("calendar.google.com", "outlook.live.com", "outlook.office365.com", "caldav.fastmail.com")

  private def hostAllowed(host: String): Boolean =
    AllowedHosts.contains(host) || ICloudPattern.matches(host)

  def fetchEvents(url: String): Action[AnyContent] = secured.async {
    val fetchUrl = url.replace("webcal://", "https://")
    val parsed   = scala.util.Try(new java.net.URI(fetchUrl)).toOption

    val hostOk = parsed.exists { uri =>
      uri.getScheme == "https" && Option(uri.getHost).exists(hostAllowed)
    }

    if (!hostOk) {
      scala.concurrent.Future.successful(BadRequest(Json.obj("error" -> "URL must be an https:// link from a supported calendar provider")))
    } else {
      ws.url(fetchUrl)
        .withRequestTimeout(scala.concurrent.duration.Duration(10, "seconds"))
        .withFollowRedirects(false)
        .get()
        .map { response =>
          val now    = java.time.Instant.now()
          val events = IcsParser.parse(response.body)
            .filter { ev =>
              if (ev.dtStart.isEmpty) false
              else scala.util.Try(java.time.Instant.parse(ev.dtStart)).map(!_.isBefore(now))
                .orElse(scala.util.Try(java.time.LocalDate.parse(ev.dtStart)
                  .atStartOfDay(java.time.ZoneOffset.UTC).toInstant).map(!_.isBefore(now)))
                .getOrElse(false)
            }
            .sortBy(_.dtStart)
            .map { ev =>
              Json.obj(
                "id"       -> ev.uid,
                "summary"  -> ev.summary,
                "start"    -> Json.obj(
                  "dateTime" -> (if ev.dtStart.contains("T") then ev.dtStart else JsNull),
                  "date"     -> (if !ev.dtStart.contains("T") then ev.dtStart else JsNull)),
                "end"      -> Json.obj(
                  "dateTime" -> (if ev.dtEnd.contains("T") then ev.dtEnd else JsNull),
                  "date"     -> (if !ev.dtEnd.contains("T") then ev.dtEnd else JsNull)),
                "location" -> ev.location.fold[JsValue](JsNull)(JsString.apply)
              )
            }
          Ok(Json.obj("events" -> JsArray(events)))
        }.recover { case ex =>
          BadGateway(Json.obj("error" -> ex.getMessage))
        }
    }
  }

}
