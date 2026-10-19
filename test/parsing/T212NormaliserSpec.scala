package parsing

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

class T212NormaliserSpec extends AnyWordSpec with Matchers {

  private val sampleItem = Json.obj(
    "ticker"       -> "AAPL_US_EQ",
    "quantity"     -> 2.0,
    "averagePrice" -> 150.0,
    "currentPrice" -> 180.0,
    "ppl"          -> 60.0
  )

  "T212Normaliser.normalise" should {
    "parse a raw JsArray response" in {
      val positions = T212Normaliser.normalise(JsArray(Seq(sampleItem)))
      positions should have length 1
      positions.head.ticker       shouldBe "AAPL_US_EQ"
      positions.head.quantity     shouldBe 2.0
      positions.head.averagePrice shouldBe 150.0
      positions.head.currentPrice shouldBe 180.0
      positions.head.ppl          shouldBe 60.0
    }

    "parse an {items:[...]} wrapped response" in {
      val positions = T212Normaliser.normalise(Json.obj("items" -> JsArray(Seq(sampleItem))))
      positions should have length 1
      positions.head.ticker shouldBe "AAPL_US_EQ"
    }

    "calculate value as quantity * currentPrice" in {
      val positions = T212Normaliser.normalise(JsArray(Seq(sampleItem)))
      positions.head.value shouldBe (2.0 * 180.0)
    }

    "calculate pctChange correctly" in {
      val positions = T212Normaliser.normalise(JsArray(Seq(sampleItem)))
      positions.head.pctChange shouldBe (((180.0 - 150.0) / 150.0) * 100.0)
    }

    "return pctChange of 0 when averagePrice is 0" in {
      val item = sampleItem + ("averagePrice" -> JsNumber(0))
      T212Normaliser.normalise(JsArray(Seq(item))).head.pctChange shouldBe 0.0
    }

    "return empty Seq for unrecognised JSON shape" in {
      T212Normaliser.normalise(JsString("bad")) shouldBe empty
    }

    "return empty Seq for object missing items field" in {
      T212Normaliser.normalise(Json.obj("other" -> "data")) shouldBe empty
    }

    "handle multiple positions" in {
      T212Normaliser.normalise(JsArray(Seq(sampleItem, sampleItem))) should have length 2
    }
  }
}
