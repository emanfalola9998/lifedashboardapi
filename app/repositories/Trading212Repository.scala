package repositories

import play.api.db.Database
import javax.inject.*

@Singleton
class Trading212Repository @Inject()(db: Database) {

  def saveKey(userId: String, apiKey: String): Unit = db.withConnection { conn =>
    val sql = """
      INSERT INTO trading212_keys (user_id, api_key)
      VALUES (?, ?)
      ON CONFLICT (user_id) DO UPDATE SET api_key = EXCLUDED.api_key
    """
    val ps = conn.prepareStatement(sql)
    ps.setString(1, userId)
    ps.setString(2, apiKey)
    ps.executeUpdate()
    ps.close()
  }

  def getKey(userId: String): Option[String] = db.withConnection { conn =>
    val ps = conn.prepareStatement("SELECT api_key FROM trading212_keys WHERE user_id = ?")
    ps.setString(1, userId)
    val rs = ps.executeQuery()
    val result = if (rs.next()) Some(rs.getString("api_key")) else None
    rs.close(); ps.close()
    result
  }

  def deleteKey(userId: String): Unit = db.withConnection { conn =>
    val ps = conn.prepareStatement("DELETE FROM trading212_keys WHERE user_id = ?")
    ps.setString(1, userId)
    ps.executeUpdate()
    ps.close()
  }
}
