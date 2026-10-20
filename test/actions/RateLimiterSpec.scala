package actions

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import actions.RateLimiter.*

class RateLimiterSpec extends AnyWordSpec with Matchers {

  /** Sends `n` requests at time `now`, returning the final bucket state. */
  private def spend(n: Int, from: Option[Bucket], now: Long): Bucket =
    (1 to n).foldLeft(from) { (bucket, _) => Some(take(bucket, now)) }.get

  "take" should {
    "allow a first request and start with a full bucket" in {
      val b = take(None, now = 0)
      b.allowed shouldBe true
      b.tokens shouldBe Capacity - 1
    }
    "allow a burst of exactly Capacity requests, then reject" in {
      val afterBurst = spend(Capacity, None, now = 0)
      afterBurst.allowed shouldBe true
      take(Some(afterBurst), now = 0).allowed shouldBe false
    }
    "refill gradually rather than all at once" in {
      val empty = spend(Capacity, None, now = 0)
      take(Some(empty), now = 100).allowed shouldBe false    // 100ms ≈ 0.33 tokens
      take(Some(empty), now = 300).allowed shouldBe true     // 300ms = 1 token
    }
    "block a burst across the old fixed-window boundary" in {
      // The old limiter allowed 200 at 0:59 and another 200 at 1:01. Two seconds after an
      // emptied bucket, only ~6 tokens have come back.
      val empty = spend(Capacity, None, now = 59_000)
      val after = (1 to 20).scanLeft(empty)((b, _) => take(Some(b), now = 61_000)).tail
      after.count(_.allowed) shouldBe 6
    }
    "never refill beyond Capacity" in {
      val b = take(Some(Bucket(tokens = 0, lastRefill = 0, allowed = true)), now = 10 * 60_000)
      b.tokens shouldBe Capacity - 1
    }
  }

  "isIdle" should {
    "be true only once a bucket has fully refilled" in {
      val used = spend(50, None, now = 0)
      isIdle(used, now = 1_000) shouldBe false
      isIdle(used, now = 60_000) shouldBe true
    }
  }

  "retryAfterSeconds" should {
    "be at least 1 second" in {
      val empty = spend(Capacity + 1, None, now = 0)
      retryAfterSeconds(empty) shouldBe 1L
    }
  }
}
