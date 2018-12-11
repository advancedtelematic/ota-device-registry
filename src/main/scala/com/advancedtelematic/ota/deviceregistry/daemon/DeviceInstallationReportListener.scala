package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceInstallationReportEncoder
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository
import io.circe.syntax._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceInstallationReportListener()(implicit val db: Database, ec: ExecutionContext) extends (DeviceInstallationReport => Future[Unit]) {

  override def apply(msg: DeviceInstallationReport): Future[Unit] = db.run {
    InstallationReportRepository
      .saveInstallationResults(msg.correlationId, msg.device, msg.result.code, msg.result.success, msg.ecuReports, msg.receivedAt, msg.asJson)
      .map(_ => Done)
  }
}
