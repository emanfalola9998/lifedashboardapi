package repositories

import anorm.*
import anorm.SqlParser.str
import play.api.db.Database
import javax.inject.*
import scala.concurrent.Future

@Singleton
class Trading212Repository @Inject()(db: Database)(implicit ec: DatabaseExecutionContext) {

  def saveKey(userId: String, apiKey: String): Future[Unit] = Future {
    db.withConnection { implicit conn =>
      SQL(
        """INSERT INTO trading212_keys (user_id, api_key) VALUES ({userId}, {apiKey})
          |ON CONFLICT (user_id) DO UPDATE SET api_key = EXCLUDED.api_key""".stripMargin)
        .on("userId" -> userId, "apiKey" -> apiKey)
        .executeUpdate()
    }
  }

  def getKey(userId: String): Future[Option[String]] = Future {
    db.withConnection { implicit conn =>
      SQL("SELECT api_key FROM trading212_keys WHERE user_id = {userId}")
        .on("userId" -> userId)
        .as(str("api_key").singleOpt)
    }
  }

  def hasKey(userId: String): Future[Boolean] =
    getKey(userId).map(_.isDefined)

  def deleteKey(userId: String): Future[Unit] = Future {
    db.withConnection { implicit conn =>
      SQL("DELETE FROM trading212_keys WHERE user_id = {userId}")
        .on("userId" -> userId)
        .executeUpdate()
    }
  }
}
