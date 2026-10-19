package actions

import play.api.mvc.*
import play.api.libs.json.Json
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SecuredAction @Inject()(
  config: play.api.Configuration,
  action: DefaultActionBuilder,
  ec: ExecutionContext
) {
  private val secret = config.get[String]("internal.apiSecret")

  private def unauthorised: Result =
    Results.Unauthorized(Json.obj("error" -> "Unauthorized"))

  private def check(r: RequestHeader): Boolean =
    r.headers.get("X-Internal-Secret").contains(secret)

  /** Wraps a synchronous block (no request access). */
  def apply(block: => Result): Action[AnyContent] = action { request =>
    if (check(request)) block else unauthorised
  }

  /** Wraps a synchronous block with access to the request. */
  def apply(block: Request[AnyContent] => Result): Action[AnyContent] = action { request =>
    if (check(request)) block(request) else unauthorised
  }

  /** Wraps an async block with access to the request. */
  def async(block: Request[AnyContent] => Future[Result]): Action[AnyContent] = action.async { request =>
    if (check(request)) block(request)
    else Future.successful(unauthorised)
  }

  /** Async with a custom body parser (e.g. parse.json). */
  def async[A](bp: BodyParser[A])(block: Request[A] => Future[Result]): Action[A] = action.async(bp) { request =>
    if (check(request)) block(request)
    else Future.successful(unauthorised)
  }

  /** Wraps an async block that ignores the request. */
  def async(block: => Future[Result]): Action[AnyContent] = action.async { request =>
    if (check(request)) block
    else Future.successful(unauthorised)
  }
}
