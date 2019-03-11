package com.advancedtelematic.ota.deviceregistry.db

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceInstallationReport, DeviceUpdateCompleted}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceInstallationReportDecoder
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceUpdateCompletedEncoder
import com.advancedtelematic.libats.slick.db.SlickCirceMapper._
import com.advancedtelematic.libats.slick.db.SlickUrnMapper.correlationIdMapper
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository.deviceInstallationResults
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository.updateInstallationResultReport
import io.circe.syntax._
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class MigrateDeviceUpdateCompleted()(implicit db: Database, ec: ExecutionContext, mat: Materializer) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  def run : Future[Done] = {
    val source = db.stream(
      deviceInstallationResults.map(d => (d.correlationId, d.deviceUuid, d.installationReport)).result)
    Source
      .fromPublisher(source)
      .mapAsyncUnordered(1) { case (correlationId, deviceUuid, installationReport) =>
        val q = installationReport.as[DeviceInstallationReport] match {
          case Right(report) => {
            _log.debug(s"Migrate installation report for $deviceUuid, $correlationId")
            val deviceUpdateCompleted = DeviceUpdateCompleted(
              report.namespace,
              report.receivedAt,
              report.correlationId,
              report.device,
              report.result,
              report.ecuReports,
              None)
            updateInstallationResultReport(correlationId, deviceUuid, deviceUpdateCompleted.asJson)
          }
          case _ => {
            _log.debug(s"Ignore installation report for $deviceUuid, $correlationId")
            DBIO.successful(())
          }
        }
        Future.successful(q)
      }
      .runForeach(q => db.run(q))
  }
}
