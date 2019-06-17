package db.migration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.slick.db.AppMigration
import com.advancedtelematic.ota.deviceregistry.db.MigrateDeviceUpdateCompleted
import slick.jdbc.MySQLProfile

import scala.concurrent.Future

class R__DeviceUpdateCompletedMigration extends AppMigration with ServiceHttpClientSupport {

  implicit val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  override def migrate(implicit db: MySQLProfile.api.Database): Future[Unit] =
    new MigrateDeviceUpdateCompleted().run.map(_ => ())
}
