package services

import repositories.DashboardRepository
import play.api.cache.AsyncCacheApi
import javax.inject.*
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DashboardService @Inject()(
  dashboardRepository: DashboardRepository,
  cache: AsyncCacheApi
)(implicit ec: ExecutionContext) {

  private val CacheTtl = 60.seconds

  def getData(id: String): Future[Option[String]] =
    cache.getOrElseUpdate[Option[String]](cacheKey(id), CacheTtl) {
      Future(dashboardRepository.getData(id))
    }

  def saveData(id: String, data: String): Future[Unit] =
    Future {
      dashboardRepository.saveData(id, data)
    }.flatMap { _ =>
      cache.set(cacheKey(id), Some(data), CacheTtl).map(_ => ())
    }

  private def cacheKey(id: String) = s"dashboard:$id"
}
