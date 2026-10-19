package parsing

import play.api.libs.json.*

object IcsParser {

  case class IcsEvent(
    uid: String,
    summary: String,
    dtStart: String,
    dtEnd: String,
    location: Option[String]
  )

  def parse(text: String): List[IcsEvent] = {
    val unfolded = text.replace("\r\n ", "").replace("\r\n\t", "")
      .replace("\n ", "").replace("\n\t", "")
    val lines = unfolded.split("\r\n|\n").toList

    var events  = List.empty[IcsEvent]
    var current = Option.empty[IcsEvent]

    for (line <- lines) {
      if (line == "BEGIN:VEVENT") {
        current = Some(IcsEvent("", "", "", "", None))
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
    events
  }

  def toIso(raw: String): String = {
    val s = raw.trim
    if (s.length == 8) {
      s"${s.substring(0,4)}-${s.substring(4,6)}-${s.substring(6,8)}"
    } else if (s.endsWith("Z") && s.length >= 15) {
      val d = s.replace("Z", "")
      s"${d.substring(0,4)}-${d.substring(4,6)}-${d.substring(6,8)}T${d.substring(9,11)}:${d.substring(11,13)}:${d.substring(13,15)}Z"
    } else if (s.contains("T") && s.length >= 15) {
      val d = s.takeWhile(_ != 'Z')
      s"${d.substring(0,4)}-${d.substring(4,6)}-${d.substring(6,8)}T${d.substring(9,11)}:${d.substring(11,13)}:${d.substring(13,15)}Z"
    } else s
  }
}
