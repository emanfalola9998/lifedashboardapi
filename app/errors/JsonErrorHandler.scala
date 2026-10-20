package errors

import play.api.{Environment, Logging, Mode}
import play.api.http.HttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.mvc.Results.*
import javax.inject.*
import scala.concurrent.Future

/** Turns every unhandled error into a JSON response and logs it.
  *
  * Without this, Play's default handler returns an HTML error page, which the
  * frontend can't parse. Registered via `play.http.errorHandler` in application.conf.
  */
@Singleton
class JsonErrorHandler @Inject()(env: Environment) extends HttpErrorHandler with Logging {

  /** 4xx raised by Play itself, before our code runs: bad JSON, body too large, no matching route. */
  def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    logger.warn(s"[${request.id}] $statusCode on ${request.method} ${request.path}: $message")
    val error = if (message.nonEmpty) message else statusCode match {
      case 404 => "Not found"
      case 413 => "Request body too large"
      case _ => "Bad request"
    }    
    Future.successful(Status(statusCode)(Json.obj("error" -> error, "requestId" -> request.id)))
  }

  /** An exception or failed Future that no controller recovered from. */
  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    logger.error(s"[${request.id}] Unhandled error on ${request.method} ${request.path}", exception)

    // Only expose the exception message locally — in production it could leak SQL or internals
    val body =
      if (env.mode == Mode.Dev) Json.obj("error" -> exception.getMessage, "requestId" -> request.id)
      else Json.obj("error" -> "Internal server error", "requestId" -> request.id)

    Future.successful(InternalServerError(body))
  }
}
