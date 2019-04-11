package db.migration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.slick.db.AppMigration
import com.advancedtelematic.ota.deviceregistry.client.AuditorHttpClient
import com.advancedtelematic.ota.deviceregistry.db.MigrateOldInstallationReports
import com.typesafe.config.{Config, ConfigFactory}
import slick.jdbc.MySQLProfile.api.Database

import scala.concurrent.Future

class R__AuditorInstallationReportsMigration extends AppMigration with ServiceHttpClientSupport {

  implicit val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  private val config: Config = ConfigFactory.load()
  private val migrateAuditor = config.getBoolean("auditor.migrate")
  private val auditorUri = config.getString("auditor.uri")
  private val auditor = new AuditorHttpClient(auditorUri, defaultHttpClient)

  override def migrate(implicit db: Database): Future[Unit] =
    if (migrateAuditor)
      new MigrateOldInstallationReports(auditor).run.map(_ => ())
    else
      Future.unit
}
