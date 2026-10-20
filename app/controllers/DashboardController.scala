package controllers

import play.api.mvc.*
import play.api.libs.json.{JsValue, Json}
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import services.DashboardService
import repositories.SaveCondition
import actions.{RateLimiter, SecuredAction}

@Singleton
class DashboardController @Inject()(
  cc: ControllerComponents,
  dashboardService: DashboardService,
  rateLimiter: RateLimiter,
  secured: SecuredAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  // The version is sent as an ETag; the client echoes it back in If-Match when saving
  private def etag(version: Long) = s"\"$version\""

  def getData(id: String): Action[AnyContent] = secured.async { request =>
    rateLimiter.checkOrReject(request) { () =>
      dashboardService.getData(id).map {
        case Some(record) => Ok(record.data).as(JSON).withHeaders(ETAG -> etag(record.version))
        case None         => NotFound(Json.obj("error" -> "No dashboard data"))
      }
    }
  }

  def saveData(id: String): Action[JsValue] = secured.async(parse.json) { request =>
    rateLimiter.checkOrReject(request) { () =>
      SaveCondition.fromHeaders(request.headers.get(IF_MATCH), request.headers.get(IF_NONE_MATCH)) match {
        case Left(error) => Future.successful(BadRequest(Json.obj("error" -> error)))
        case Right(condition) =>
          dashboardService.saveData(id, request.body.toString, condition).map {
            case Some(version) => NoContent.withHeaders(ETAG -> etag(version))
            case None => PreconditionFailed(Json.obj("error" -> "Dashboard was changed elsewhere — reload to get the latest version"))
          }
      }
    }
  }
}
