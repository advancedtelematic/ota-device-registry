package db.migration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.slick.db.AppMigration
import com.advancedtelematic.ota.deviceregistry.client.SotaCoreHttpClient
import com.advancedtelematic.ota.deviceregistry.db.MigrateBlacklistedPackages
import com.typesafe.config.ConfigFactory
import slick.jdbc.MySQLProfile

import scala.concurrent.Future

class R__SotaCoreBlacklistedPackagesMigration extends AppMigration with ServiceHttpClientSupport {

  implicit val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  private val sotaCoreUri = ConfigFactory.load().getString("sota_core.uri")
  private val sotaCore = new SotaCoreHttpClient(sotaCoreUri, defaultHttpClient)

  override def migrate(implicit db: MySQLProfile.api.Database): Future[Unit] =
    new MigrateBlacklistedPackages(sotaCore).run.map(_ => ())
}
