package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libtuf.data.TufDataType.OperationResult
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, DeviceReport, EcuReport, UpdateStat}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.AbstractTable

import scala.concurrent.ExecutionContext

object UpdateReportRepository {

  trait ReportTable {
    def correlationId: Rep[CorrelationId]
    def resultCode: Rep[Int]
  }

  class DeviceUpdateReportTable(tag: Tag) extends Table[DeviceReport](tag, "DeviceReport") with ReportTable {
    def deviceUuid    = column[DeviceId]("device_uuid")
    def correlationId = column[CorrelationId]("correlation_id")
    def resultCode    = column[Int]("result_code")

    def * = (deviceUuid, correlationId, resultCode) <> ((DeviceReport.apply _).tupled, DeviceReport.unapply)

    def pk = primaryKey("pk_device_report", (deviceUuid, correlationId))
  }

  private val deviceUpdateReports = TableQuery[DeviceUpdateReportTable]

  class EcuUpdateReportTable(tag: Tag) extends Table[EcuReport](tag, "EcuReport") with ReportTable {
    def ecuSerial     = column[EcuSerial]("ecu_serial")
    def correlationId = column[CorrelationId]("correlation_id")
    def resultCode    = column[Int]("result_code")

    def * = (ecuSerial, correlationId, resultCode) <> ((EcuReport.apply _).tupled, EcuReport.unapply)

    def pk = primaryKey("pk_ecu_report", (ecuSerial, correlationId))
  }

  private val ecuUpdateReports = TableQuery[EcuUpdateReportTable]

  def saveUpdateReport(correlationId: CorrelationId,
                       deviceUuid: DeviceId,
                       deviceResultCode: Int,
                       operationResults: Map[EcuSerial, OperationResult])(implicit ec: ExecutionContext): DBIO[Unit] = {
    val q =
      for {
        _ <- deviceUpdateReports += DeviceReport(deviceUuid, correlationId, deviceResultCode)
        _ <- ecuUpdateReports ++= operationResults.map {
          case (ecuSerial, operationResult) => EcuReport(ecuSerial, correlationId, operationResult.resultCode)
        }
      } yield ()
    q.transactionally
  }

  private def statsQuery[T <: AbstractTable[_]](tableQuery: TableQuery[T], correlationId: CorrelationId)
                                               (implicit ec: ExecutionContext, ev: T <:< ReportTable) =
    tableQuery
      .map { r => (r.correlationId, r.resultCode) }
      .filter { case (_correlationId, _)  => _correlationId === correlationId }
      .groupBy { case (_, resultCode) => resultCode }
      .map { case (resultCode, failedDevices) => (resultCode, failedDevices.length) }
      .result
      .map(_.map { case (resultCode, failedDevices) => UpdateStat(resultCode, failedDevices) } )

  def calculateFailedDevicesStats(correlationId: CorrelationId)(implicit ec: ExecutionContext): DBIO[Seq[UpdateStat]] =
    statsQuery(deviceUpdateReports, correlationId)

  def calculateFailedEcuStats(correlationId: CorrelationId)(implicit ec: ExecutionContext): DBIO[Seq[UpdateStat]] = {
    statsQuery(ecuUpdateReports, correlationId)
  }
}
