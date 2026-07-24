package controllers

import play.api.mvc.*
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import services.DashboardService
import filters.RateLimitFilter

@Singleton
class DashboardController @Inject()(
  cc: ControllerComponents,
  dashboardService: DashboardService,
  rateLimiter: RateLimitFilter
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def getData(id: String): Action[AnyContent] = rateLimiter {
    Action.async { implicit request =>
      dashboardService.getData(id).map {
        case Some(data) => Ok(data).as("application/json")
        case None       => Ok("{}").as("application/json")
      }
    }
  }

  def saveData(id: String): Action[AnyContent] = rateLimiter {
    Action.async { implicit request =>
      request.body.asJson.map(_.toString) match {
        case Some(json) =>
          dashboardService.saveData(id, json).map(_ => Ok("saved"))
        case None =>
          Future.successful(BadRequest("data is missing"))
      }
    }
  }
}
