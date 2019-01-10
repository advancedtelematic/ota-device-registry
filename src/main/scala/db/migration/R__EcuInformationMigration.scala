package db.migration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.slick.db.AppMigration
import com.advancedtelematic.ota.deviceregistry.client.DirectorHttpClient
import com.advancedtelematic.ota.deviceregistry.db.MigrateEcuInfoFromDirector
import com.typesafe.config.ConfigFactory
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.Future

class R__EcuInformationMigration extends AppMigration with ServiceHttpClientSupport {

  implicit val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  private val directorUri = ConfigFactory.load().getString("director.uri")
  private val director = new DirectorHttpClient(directorUri, defaultHttpClient)

  override def migrate(implicit db: Database): Future[Unit] =
    new MigrateEcuInfoFromDirector(director).run.map(_ => ())
}
