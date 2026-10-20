package services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import repositories.DashboardRecord
import services.DashboardCache.TtlMs

class DashboardCacheSpec extends AnyWordSpec with Matchers {

  private val v3 = Some(DashboardRecord("""{"goals":[]}""", 3))
  private val v4 = Some(DashboardRecord("""{"goals":["new"]}""", 4))

  "DashboardCache" should {
    "return a cached record" in {
      val cache = new DashboardCache
      cache.put("u", v3, now = 0)
      cache.get("u", now = 1_000) shouldBe Some(v3)
    }
    "treat a missing entry as a miss" in {
      new DashboardCache().get("u", now = 0) shouldBe None
    }
    "cache 'no dashboard yet' separately from a miss" in {
      val cache = new DashboardCache
      cache.put("u", None, now = 0)
      cache.get("u", now = 0) shouldBe Some(None)
    }
    "expire entries after the TTL" in {
      val cache = new DashboardCache
      cache.put("u", v3, now = 0)
      cache.get("u", now = TtlMs + 1) shouldBe None
    }
    "replace an older version with a newer one" in {
      val cache = new DashboardCache
      cache.put("u", v3, now = 0)
      cache.put("u", v4, now = 10) shouldBe v4
      cache.get("u", now = 10) shouldBe Some(v4)
    }
    "not let a slow read overwrite a newer save" in {
      // A read fetched v3, then a save cached v4, then the read finishes and tries to cache v3
      val cache = new DashboardCache
      cache.put("u", v4, now = 10)
      cache.put("u", v3, now = 20) shouldBe v4
      cache.get("u", now = 20) shouldBe Some(v4)
    }
    "replace a cached 'no dashboard yet' once one is saved" in {
      val cache = new DashboardCache
      cache.put("u", None, now = 0)
      cache.put("u", v3, now = 10) shouldBe v3
    }
    "let anything replace an expired entry" in {
      val cache = new DashboardCache
      cache.put("u", v4, now = 0)
      cache.put("u", v3, now = TtlMs + 1) shouldBe v3
    }
    "forget an entry on remove" in {
      val cache = new DashboardCache
      cache.put("u", v3, now = 0)
      cache.remove("u")
      cache.get("u", now = 0) shouldBe None
    }
  }
}
