package db.migration
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.slick.db.AppMigration
import com.advancedtelematic.ota.deviceregistry.client.AuditorHttpClient
import com.advancedtelematic.ota.deviceregistry.db.MigrateOldInstallationReports
import com.typesafe.config.ConfigFactory
import slick.jdbc.MySQLProfile

import scala.concurrent.Future

class R__AuditorInstallationReportsMigration extends AppMigration with ServiceHttpClientSupport {

  implicit val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  private val auditorUri = ConfigFactory.load().getString("auditor.uri")
  private val auditor = new AuditorHttpClient(auditorUri, defaultHttpClient)

  override def migrate(implicit db: MySQLProfile.api.Database): Future[Unit] =
    new MigrateOldInstallationReports(auditor).run.map(_ => ())
}
