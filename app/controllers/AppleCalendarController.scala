package controllers

import play.api.mvc.*
import play.api.libs.json.*
import play.api.libs.ws.*
import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class AppleCalendarController @Inject()(
  cc: ControllerComponents,
  ws: WSClient,
  ec: ExecutionContext
) extends AbstractController(cc) {

  given ExecutionContext = ec

  def fetchEvents(url: String): Action[AnyContent] = Action.async {
    val fetchUrl = url.replace("webcal://", "https://")
    ws.url(fetchUrl).get().map { response =>
      val events = parseIcs(response.body)
      Ok(Json.obj("events" -> events))
    }.recover { case ex =>
      BadGateway(Json.obj("error" -> ex.getMessage))
    }
  }

  private def parseIcs(text: String): JsArray = {
    // Unfold folded lines (lines continued with whitespace)
    val unfolded = text.replace("\r\n ", "").replace("\r\n\t", "")
      .replace("\n ", "").replace("\n\t", "")
    val lines = unfolded.split("\r\n|\n").toList

    case class Event(
      summary: String = "",
      dtStart: String = "",
      dtEnd:   String = "",
      location: Option[String] = None,
      uid: String = ""
    )

    var events  = List.empty[Event]
    var current = Option.empty[Event]

    for (line <- lines) {
      if (line == "BEGIN:VEVENT") {
        current = Some(Event())
      } else if (line == "END:VEVENT") {
        current.foreach(ev => events = events :+ ev)
        current = None
      } else {
        current = current.map { ev =>
          val (key, value) = line.span(_ != ':') match {
            case (k, v) => (k.takeWhile(_ != ';').toUpperCase, v.dropWhile(_ == ':'))
          }
          key match {
            case "SUMMARY"  => ev.copy(summary  = value)
            case "DTSTART"  => ev.copy(dtStart  = toIso(value))
            case "DTEND"    => ev.copy(dtEnd    = toIso(value))
            case "LOCATION" => ev.copy(location = Some(value))
            case "UID"      => ev.copy(uid      = value)
            case _          => ev
          }
        }
      }
    }

    // Filter to events starting within the next 14 days
    val now    = java.time.Instant.now()
    val cutoff = now.plus(java.time.Duration.ofDays(14))

    val filtered = events.filter { ev =>
      try {
        val instant = java.time.Instant.parse(ev.dtStart)
        !instant.isBefore(now) && instant.isBefore(cutoff)
      } catch { case _: Exception =>
        // All-day events have date-only strings like 2025-07-26
        try {
          val date = java.time.LocalDate.parse(ev.dtStart)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant
          !date.isBefore(now) && date.isBefore(cutoff)
        } catch { case _: Exception => false }
      }
    }.sortBy(_.dtStart)

    JsArray(filtered.map { ev =>
      Json.obj(
        "id"      -> ev.uid,
        "summary" -> ev.summary,
        "start"   -> Json.obj("dateTime" -> (if ev.dtStart.contains("T") then ev.dtStart else JsNull),
                              "date"      -> (if !ev.dtStart.contains("T") then ev.dtStart else JsNull)),
        "end"     -> Json.obj("dateTime" -> (if ev.dtEnd.contains("T") then ev.dtEnd else JsNull),
                              "date"      -> (if !ev.dtEnd.contains("T") then ev.dtEnd else JsNull)),
        "location" -> ev.location.fold[JsValue](JsNull)(JsString.apply)
      )
    })
  }

  // Convert ICS datetime (20250726T140000Z or 20250726) to ISO-8601
  private def toIso(raw: String): String = {
    val s = raw.trim
    if (s.length == 8) {
      // Date-only: YYYYMMDD
      s"${s.substring(0,4)}-${s.substring(4,6)}-${s.substring(6,8)}"
    } else if (s.endsWith("Z") && s.length >= 15) {
      // UTC datetime: YYYYMMDDTHHmmssZ
      val d = s.replace("Z", "")
      s"${d.substring(0,4)}-${d.substring(4,6)}-${d.substring(6,8)}T${d.substring(9,11)}:${d.substring(11,13)}:${d.substring(13,15)}Z"
    } else if (s.contains("T") && s.length >= 15) {
      // Local datetime without Z: YYYYMMDDTHHmmss
      val d = s.takeWhile(_ != 'Z')
      s"${d.substring(0,4)}-${d.substring(4,6)}-${d.substring(6,8)}T${d.substring(9,11)}:${d.substring(11,13)}:${d.substring(13,15)}Z"
    } else s
  }
}
