package controllers

import play.api.mvc.*
import javax.inject.*
import services.DashboardService


class DashboardController @Inject()(cc: ControllerComponents, dashboardService: DashboardService) extends AbstractController(cc) {

  def getData(id:String): Action[AnyContent] = Action {
    implicit request =>
     dashboardService.getData(id) match {
       case Some(data) => Ok(data).as("application/json")
       case None => NotFound("No data found for this user")
     }
  }
  
  def saveData(id: String): Action[AnyContent] = Action {
    implicit request =>
      request.body.asJson.map(_.toString) match {
        case Some(json) => 
          dashboardService.saveData(id, json)
          Ok("saved")
        case None => BadRequest("data is missing")
      }
  }

}
