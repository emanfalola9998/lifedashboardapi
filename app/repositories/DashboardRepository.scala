package repositories

import anorm.*
import anorm.SqlParser.{long, str}
import javax.inject.*
import play.api.db.Database
import scala.concurrent.Future

/** A user's dashboard JSON plus the version it was saved at. */
final case class DashboardRecord(data: String, version: Long)

/** What a save is allowed to overwrite. */
enum SaveCondition {
  /** Only if the stored version is still this one (the client's If-Match). */
  case IfVersion(version: Long)
  /** Only if no row exists yet (the client's If-None-Match: *) — a new user's first save. */
  case IfAbsent
  /** Overwrite whatever is there. Kept so a frontend deployed before versioning keeps saving;
    * remove once every client sends a condition.
    */
  case Unconditional
}

object SaveCondition {
  /** Reads the HTTP precondition headers. ETags look like `"3"` (optionally `W/"3"`). */
  def fromHeaders(ifMatch: Option[String], ifNoneMatch: Option[String]): Either[String, SaveCondition] =
    (ifMatch, ifNoneMatch) match {
      case (Some(tag), _) =>
        tag.trim.stripPrefix("W/").stripPrefix("\"").stripSuffix("\"").toLongOption
          .map(IfVersion(_))
          .toRight(s"Invalid If-Match header: $tag")
      case (None, Some("*")) => Right(IfAbsent)
      case _                 => Right(Unconditional)
    }
}

@Singleton
class DashboardRepository @Inject()(db: Database)(implicit ec: DatabaseExecutionContext) {

  given Column[String] = Column.nonNull { (value, meta) =>
    value match {
      case pg: org.postgresql.util.PGobject => Right(pg.getValue)
      case str: String => Right(str)
      case _ => Left(TypeDoesNotMatch(s"Cannot convert $value to String"))
    }
  }

  private val record = (str("data") ~ long("version")).map { case data ~ version => DashboardRecord(data, version) }

  def getData(id: String): Future[Option[DashboardRecord]] = Future {
    db.withConnection { implicit conn =>
      SQL("SELECT data, version FROM dashboard_data WHERE id = {id}")
        .on("id" -> id)
        .as(record.singleOpt)
    }
  }

  /** Returns the new version, or None if the condition failed (someone else saved first). Each
    * statement checks and writes in one step, so two saves can't both pass the check.
    */
  def saveData(id: String, data: String, condition: SaveCondition): Future[Option[Long]] = Future {
    db.withConnection { implicit conn =>
      val newVersion = long("version").singleOpt
      condition match {
        case SaveCondition.IfVersion(expected) =>
          SQL(
            """UPDATE dashboard_data
              |SET data = {data}::jsonb, version = version + 1, updated_at = NOW()
              |WHERE id = {id} AND version = {expected}
              |RETURNING version""".stripMargin)
            .on("id" -> id, "data" -> data, "expected" -> expected)
            .as(newVersion)

        case SaveCondition.IfAbsent =>
          SQL(
            """INSERT INTO dashboard_data (id, data) VALUES ({id}, {data}::jsonb)
              |ON CONFLICT (id) DO NOTHING
              |RETURNING version""".stripMargin)
            .on("id" -> id, "data" -> data)
            .as(newVersion)

        case SaveCondition.Unconditional =>
          SQL(
            """INSERT INTO dashboard_data (id, data) VALUES ({id}, {data}::jsonb)
              |ON CONFLICT (id) DO UPDATE
              |SET data = EXCLUDED.data, version = dashboard_data.version + 1, updated_at = NOW()
              |RETURNING version""".stripMargin)
            .on("id" -> id, "data" -> data)
            .as(newVersion)
      }
    }
  }
}
