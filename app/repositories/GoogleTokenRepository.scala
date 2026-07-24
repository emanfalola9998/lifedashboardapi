package repositories

import anorm.*
import anorm.SqlParser.*
import javax.inject.*
import play.api.db.Database

case class GoogleToken(
  userId: String,
  accessToken: String,
  refreshToken: Option[String],
  expiresAt: Long
)

class GoogleTokenRepository @Inject()(db: Database) {

  private val tokenParser = (
    str("user_id") ~
    str("access_token") ~
    str("refresh_token").? ~
    long("expires_at")
  ).map { case userId ~ accessToken ~ refreshToken ~ expiresAt =>
    GoogleToken(userId, accessToken, refreshToken, expiresAt)
  }

  def getToken(userId: String): Option[GoogleToken] = {
    db.withConnection { implicit conn =>
      SQL("SELECT user_id, access_token, refresh_token, expires_at FROM google_tokens WHERE user_id = {userId}")
        .on("userId" -> userId)
        .as(tokenParser.singleOpt)
    }
  }

  def upsertToken(token: GoogleToken): Unit = {
    db.withConnection { implicit conn =>
      SQL("""
        INSERT INTO google_tokens (user_id, access_token, refresh_token, expires_at, updated_at)
        VALUES ({userId}, {accessToken}, {refreshToken}, {expiresAt}, NOW())
        ON CONFLICT (user_id) DO UPDATE SET
          access_token  = EXCLUDED.access_token,
          refresh_token = COALESCE(EXCLUDED.refresh_token, google_tokens.refresh_token),
          expires_at    = EXCLUDED.expires_at,
          updated_at    = NOW()
      """)
        .on(
          "userId"       -> token.userId,
          "accessToken"  -> token.accessToken,
          "refreshToken" -> token.refreshToken,
          "expiresAt"    -> token.expiresAt
        )
        .executeUpdate()
    }
  }

  def deleteToken(userId: String): Unit = {
    db.withConnection { implicit conn =>
      SQL("DELETE FROM google_tokens WHERE user_id = {userId}")
        .on("userId" -> userId)
        .executeUpdate()
    }
  }
}
