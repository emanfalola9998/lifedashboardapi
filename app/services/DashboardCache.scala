package services

import repositories.DashboardRecord
import java.util.concurrent.ConcurrentHashMap
import javax.inject.*

/** In-memory cache of dashboards, keyed by user id, that never replaces a newer version with an older one.
  *
  * Play's AsyncCacheApi can only "set", so a slow read that finishes after a save would overwrite
  * the save's newer copy. Here, `put` keeps whichever entry has the higher version, and
  * ConcurrentHashMap.merge does that compare-and-replace atomically (like RateLimiter's compute).
  *
  * Lives in this process only: fine for one instance, but several instances would need a shared cache.
  */
@Singleton
class DashboardCache {
  import DashboardCache.*

  private val entries = new ConcurrentHashMap[String, Entry]()

  /** None = not cached or expired. Some(None) = cached "this user has no dashboard yet". */
  def get(id: String, now: Long = System.currentTimeMillis()): Option[Option[DashboardRecord]] =
    Option(entries.get(id)).filterNot(_.isExpired(now)).map(_.record)

  /** Caches `record` unless a fresher, newer version is already cached. Returns whichever one won. */
  def put(id: String, record: Option[DashboardRecord], now: Long = System.currentTimeMillis()): Option[DashboardRecord] =
    entries.merge(id, Entry(record, now), (existing, incoming) => if (keepExisting(existing, incoming, now)) existing else incoming).record

  def remove(id: String): Unit = entries.remove(id)
}

object DashboardCache {
  val TtlMs: Long = 60_000L

  final case class Entry(record: Option[DashboardRecord], storedAt: Long) {
    def isExpired(now: Long): Boolean = now - storedAt > TtlMs
    // "No dashboard yet" counts as version 0, older than any saved row (rows start at 1)
    def version: Long = record.map(_.version).getOrElse(0L)
  }

  /** Keep the cached entry only if it's still fresh and strictly newer than the incoming one. */
  def keepExisting(existing: Entry, incoming: Entry, now: Long): Boolean =
    !existing.isExpired(now) && existing.version > incoming.version
}
