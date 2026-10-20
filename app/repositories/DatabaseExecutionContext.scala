package repositories

import org.apache.pekko.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import javax.inject.*

/** Dedicated thread pool for blocking JDBC calls (configured under `database.dispatcher`).
  *
  * JDBC blocks the calling thread until Postgres replies. Running it on Play's default
  * ExecutionContext would tie up the threads that handle every other request.
  */
@Singleton
class DatabaseExecutionContext @Inject()(system: ActorSystem)
  extends CustomExecutionContext(system, "database.dispatcher")
