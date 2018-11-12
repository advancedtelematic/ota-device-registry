package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceInstallationReportEncoder
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository
import io.circe.syntax._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceInstallationReportListener()(implicit val db: Database, ec: ExecutionContext) extends (DeviceInstallationReport => Future[Unit]) {

  override def apply(message: DeviceInstallationReport): Future[Unit] = db.run {
    InstallationReportRepository
      .saveInstallationResults(message.correlationId, message.device, message.result.code, message.ecuReports, message.asJson)
      .map(_ => Done)
  }
}
