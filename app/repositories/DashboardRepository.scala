package repositories

import anorm.SqlParser
import anorm.*
import javax.inject.*
import play.api.db.Database
import scala.concurrent.ExecutionContext
import anorm.Column
import anorm.TypeDoesNotMatch

class DashboardRepository @Inject()(
                           db: Database,
                           ec: ExecutionContext
                         ) {

  given Column[String] = Column.nonNull { (value, meta) =>
    value match {
      case pg: org.postgresql.util.PGobject => Right(pg.getValue)
      case str: String => Right(str)
      case _ => Left(TypeDoesNotMatch(s"Cannot convert $value to String"))
    }
  }

  def getData(id: String): Option[String] = {
    db.withConnection{
      implicit conn =>
        SQL("SELECT data FROM dashboard_data WHERE id = {id}")
          .on("id" -> id)
          .as(SqlParser.str("data").singleOpt)
    }
  }

  def saveData(id: String, data: String): Unit = {
    db.withConnection {
      implicit conn =>
        SQL(
          """
            |INSERT INTO dashboard_data (id, data) VALUES ({id}, {data}::jsonb) ON CONFLICT (id) DO UPDATE SET data = {data}::jsonb,
            |updated_at = NOW()""".stripMargin).on("id" -> id, "data" -> data).executeUpdate()

    }
  }

}

