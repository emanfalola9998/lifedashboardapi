package actions

import org.apache.pekko.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.mvc.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.*
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Per-user rate limiting with a token bucket.
  *
  * Each user has a bucket holding up to `Capacity` tokens. Every request spends one token, and
  * tokens drip back in continuously at `Capacity` per minute. A full bucket allows a burst of
  * 200 requests; after that, requests are allowed only as fast as tokens refill (~3 per second).
  *
  * Counts live in memory: they reset on restart and aren't shared between instances.
  */
@Singleton
class RateLimiter @Inject()(system: ActorSystem, lifecycle: ApplicationLifecycle) {
  import RateLimiter.*

  private val buckets = new ConcurrentHashMap[String, Bucket]()

  // Every minute, drop buckets that have refilled completely. A full bucket behaves exactly
  // like a brand-new one, so removing it changes nothing except freeing the memory.
  private val cleanup =
    system.scheduler.scheduleWithFixedDelay(1.minute, 1.minute)(() => evictIdle())(system.dispatcher)
  lifecycle.addStopHook(() => Future.successful(cleanup.cancel()))

  /** Spends a token for `key`. `compute` locks this key while the function runs, so the
    * read-refill-spend sequence is atomic — two simultaneous requests can't both read the
    * same token count.
    */
  def check(key: String): Bucket = {
    val now = System.currentTimeMillis()
    buckets.compute(key, (_, existing) => take(Option(existing), now))
  }

  def checkOrReject(request: RequestHeader)(body: () => Future[Result]): Future[Result] = {
    val bucket = check(keyFor(request))
    if (bucket.allowed) body()
    else Future.successful(
      Results.TooManyRequests(Json.obj("error" -> "Rate limit exceeded — try again shortly"))
        .withHeaders("Retry-After" -> retryAfterSeconds(bucket).toString)
    )
  }

  private def keyFor(request: RequestHeader): String =
    request.headers.get("X-User-Id")
      .orElse(request.headers.get("X-Forwarded-For"))
      .getOrElse(request.remoteAddress)

  private def evictIdle(): Unit = {
    val now = System.currentTimeMillis()
    // computeIfPresent is atomic per key: returning null removes the entry, but only if no
    // request changed it in the meantime.
    buckets.keySet.forEach { key =>
      buckets.computeIfPresent(key, (_, bucket) => if (isIdle(bucket, now)) null else bucket)
    }
  }
}

/** The bucket maths, kept as pure functions (time passed in) so they can be tested directly. */
object RateLimiter {
  val Capacity: Int          = 200
  val RefillPerMs: Double    = Capacity / 60_000.0   // a full bucket's worth per minute

  /** `allowed` records whether the request that produced this state got a token. */
  final case class Bucket(tokens: Double, lastRefill: Long, allowed: Boolean)

  def take(existing: Option[Bucket], now: Long): Bucket = {
    val available = existing match {
      case None    => Capacity.toDouble   // first request: start with a full bucket
      case Some(b) => refilled(b, now)
    }
    if (available >= 1) Bucket(available - 1, now, allowed = true)
    else Bucket(available, now, allowed = false)
  }

  def isIdle(bucket: Bucket, now: Long): Boolean = refilled(bucket, now) >= Capacity

  /** Seconds until the next token arrives — sent to the client as `Retry-After`. */
  def retryAfterSeconds(bucket: Bucket): Long =
    math.max(1L, math.ceil((1 - bucket.tokens) / RefillPerMs / 1000).toLong)

  private def refilled(bucket: Bucket, now: Long): Double =
    math.min(Capacity.toDouble, bucket.tokens + (now - bucket.lastRefill) * RefillPerMs)
}
