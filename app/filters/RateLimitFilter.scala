package filters

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.*

@Singleton
class RateLimitFilter @Inject()(implicit ec: ExecutionContext) {

  private val WINDOW_MS  = 60_000L
  private val MAX_CALLS  = 200

  private case class Window(count: AtomicLong, windowStart: AtomicLong)
  private val windows = new ConcurrentHashMap[String, Window]()

  def check(ip: String): Boolean = {
    val now = System.currentTimeMillis()
    val w   = windows.computeIfAbsent(ip, _ => Window(AtomicLong(0), AtomicLong(now)))

    if (now - w.windowStart.get() > WINDOW_MS) {
      w.windowStart.set(now)
      w.count.set(1)
      true
    } else {
      w.count.incrementAndGet() <= MAX_CALLS
    }
  }

  private def keyFor(request: RequestHeader): String =
    request.headers.get("X-User-Id")
      .orElse(request.headers.get("X-Forwarded-For"))
      .getOrElse(request.remoteAddress)

  def checkOrReject(request: RequestHeader)(body: () => Future[Result]): Future[Result] =
    if (check(keyFor(request))) body()
    else Future.successful(Results.TooManyRequests("Rate limit exceeded. Try again in a minute."))

  def apply[A](action: Action[A]): Action[A] = new Action[A] {
    def parser: BodyParser[A] = action.parser
    def apply(request: Request[A]): Future[Result] = checkOrReject(request)(() => action(request))
    override def executionContext: ExecutionContext = ec
  }
}
