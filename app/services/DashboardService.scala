package services

import repositories.DashboardRepository
import javax.inject.*

class DashboardService @Inject()(dashboardRepository: DashboardRepository) {

  def getData(id: String): Option[String] = {
    dashboardRepository.getData(id)
  }


  def saveData(id: String, data: String): Unit = {
    dashboardRepository.saveData(id, data)
  }

}
