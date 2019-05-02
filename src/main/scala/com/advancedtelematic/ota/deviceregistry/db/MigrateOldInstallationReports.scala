package com.advancedtelematic.ota.deviceregistry.db
import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.advancedtelematic.libats.data.DataType.{MultiTargetUpdateId, ResultCode, ResultDescription}
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuInstallationReport, InstallationResult, OperationResult}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceInstallationReportEncoder
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.client.{AuditorClient, DeviceUpdateReport}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.devices
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository.saveInstallationResults
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings.namespaceColumnType
import io.circe.syntax._
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class MigrateOldInstallationReports(auditor: AuditorClient)(implicit db: Database, ec: ExecutionContext, mat: Materializer) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  private def saveReport(r: DeviceInstallationReport): DBIO[Unit] = {
    _log.info(s"Saving migrated installation report: $r")
    saveInstallationResults(r.correlationId, r.device, r.result.code, r.result.success, r.ecuReports, r.receivedAt, r.asJson)
  }

  def toNewSchema(oldReport: DeviceUpdateReport): DeviceInstallationReport = {

    def toResultObject(resultCode: Int): InstallationResult = resultCode match {
      case 0 => InstallationResult(success = true, ResultCode("0"), ResultDescription("All targeted ECUs were successfully updated"))
      case _ => InstallationResult(success = false, ResultCode("19"), ResultDescription("One or more targeted ECUs failed to update"))
    }

    def toEcuInstallationReport(or: OperationResult): EcuInstallationReport =
      EcuInstallationReport(
        InstallationResult(or.resultCode == 0 || or.resultCode == 1, ResultCode(or.resultCode.toString), ResultDescription(or.resultText)),
        Seq(or.target.value),
        None)

    val newReport = DeviceInstallationReport(
      oldReport.namespace,
      oldReport.device,
      MultiTargetUpdateId(oldReport.updateId.uuid),
      toResultObject(oldReport.resultCode),
      oldReport.operationResult.mapValues(toEcuInstallationReport),
      report = None,
      oldReport.receivedAt
    )

    _log.debug(s"Transformed update report. Old format: $oldReport. New format: $newReport.")

    newReport
  }

  def run: Future[Done] = {
    val source = db.stream(devices.map(d => (d.namespace, d.uuid)).result)
    Source
      .fromPublisher(source)
      .mapAsyncUnordered(1) { case (ns, did) => auditor.getUpdateReports(ns, did)}
      .map(_.map(toNewSchema))
      .map(_.map(saveReport))
      .runForeach(_.map(db.run))
  }
}
