package repositories

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import repositories.SaveCondition.*

class SaveConditionSpec extends AnyWordSpec with Matchers {

  "SaveCondition.fromHeaders" should {
    "read a quoted ETag from If-Match" in {
      fromHeaders(Some("\"3\""), None) shouldBe Right(IfVersion(3))
    }
    "accept a weak ETag" in {
      fromHeaders(Some("W/\"7\""), None) shouldBe Right(IfVersion(7))
    }
    "prefer If-Match when both headers are sent" in {
      fromHeaders(Some("\"3\""), Some("*")) shouldBe Right(IfVersion(3))
    }
    "treat If-None-Match: * as create-only" in {
      fromHeaders(None, Some("*")) shouldBe Right(IfAbsent)
    }
    "fall back to an unconditional save when no precondition is sent" in {
      fromHeaders(None, None) shouldBe Right(Unconditional)
    }
    "reject an If-Match that isn't a version number" in {
      fromHeaders(Some("\"abc\""), None).isLeft shouldBe true
    }
  }
}
