package parsing

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class IcsParserSpec extends AnyWordSpec with Matchers {

  "IcsParser.toIso" should {
    "convert date-only YYYYMMDD" in {
      IcsParser.toIso("20251225") shouldBe "2025-12-25"
    }
    "convert UTC datetime YYYYMMDDTHHmmssZ" in {
      IcsParser.toIso("20251225T140000Z") shouldBe "2025-12-25T14:00:00Z"
    }
    "convert local datetime without Z" in {
      IcsParser.toIso("20251225T140000") shouldBe "2025-12-25T14:00:00Z"
    }
    "return unrecognised strings unchanged" in {
      IcsParser.toIso("not-a-date") shouldBe "not-a-date"
    }
  }

  "IcsParser.parse" should {
    val ics =
      """|BEGIN:VCALENDAR
         |BEGIN:VEVENT
         |UID:abc-123
         |SUMMARY:Team meeting
         |DTSTART:20251201T100000Z
         |DTEND:20251201T110000Z
         |LOCATION:London
         |END:VEVENT
         |BEGIN:VEVENT
         |UID:def-456
         |SUMMARY:All day event
         |DTSTART:20251205
         |DTEND:20251206
         |END:VEVENT
         |END:VCALENDAR""".stripMargin

    "parse all events" in {
      IcsParser.parse(ics) should have length 2
    }
    "extract fields correctly" in {
      val e = IcsParser.parse(ics).head
      e.uid      shouldBe "abc-123"
      e.summary  shouldBe "Team meeting"
      e.dtStart  shouldBe "2025-12-01T10:00:00Z"
      e.dtEnd    shouldBe "2025-12-01T11:00:00Z"
      e.location shouldBe Some("London")
    }
    "handle date-only events" in {
      val e = IcsParser.parse(ics)(1)
      e.uid     shouldBe "def-456"
      e.dtStart shouldBe "2025-12-05"
    }
    "handle folded lines" in {
      val folded =
        "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:fold-1\nSUMMARY:Long summ\n ary text\nDTSTART:20251210T090000Z\nDTEND:20251210T100000Z\nEND:VEVENT\nEND:VCALENDAR"
      val e = IcsParser.parse(folded).head
      e.summary shouldBe "Long summary text"
    }
  }
}
