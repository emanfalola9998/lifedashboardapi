package services

import repositories.{DashboardRecord, DashboardRepository, SaveCondition}
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DashboardService @Inject()(
  dashboardRepository: DashboardRepository,
  cache: DashboardCache
)(implicit ec: ExecutionContext) {

  def getData(id: String): Future[Option[DashboardRecord]] =
    cache.get(id) match {
      case Some(cached) => Future.successful(cached)
      case None =>
        // If a save cached a newer version while we were reading, put keeps that one and returns it
        dashboardRepository.getData(id).map(fromDb => cache.put(id, fromDb))
    }

  /** Returns the new version, or None if another save got there first.
    *
    * The cache is in-memory and synchronous, so updating it can't fail a save that already
    * reached the database.
    */
  def saveData(id: String, data: String, condition: SaveCondition): Future[Option[Long]] =
    dashboardRepository.saveData(id, data, condition).map {
      case Some(version) =>
        cache.put(id, Some(DashboardRecord(data, version)))
        Some(version)
      case None =>
        // Our cached copy may be the stale one the client was working from
        cache.remove(id)
        None
    }
}
