package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libtuf.data.TufDataType.OperationResult
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, DeviceReport, EcuReport, FailedStat}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object UpdateReportRepository {

  class DeviceUpdateReportTable(tag: Tag) extends Table[DeviceReport](tag, "DeviceReport") {
    def deviceUuid    = column[DeviceId]("device_uuid")
    def correlationId = column[CorrelationId]("correlation_id")
    def resultCode    = column[Int]("result_code")

    def * = (deviceUuid, correlationId, resultCode) <> ((DeviceReport.apply _).tupled, DeviceReport.unapply)

    def pk = primaryKey("pk_device_report", (deviceUuid, correlationId))
  }

  private val deviceUpdateReports = TableQuery[DeviceUpdateReportTable]

  class EcuUpdateReportTable(tag: Tag) extends Table[EcuReport](tag, "EcuReport") {
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

  def calculateFailedDevicesStats(correlationId: CorrelationId)(implicit ec: ExecutionContext): DBIO[Seq[FailedStat]] = {
    val totalDevices = deviceUpdateReports
      .filter(_.correlationId === correlationId)
      .length
      .asColumnOf[Double]

    deviceUpdateReports
      .filter(_.correlationId === correlationId)
      .filter(_.resultCode > 1)
      .groupBy(_.resultCode)
      .map {
        case (resultCode, failedDevices) =>
          val failedCount = failedDevices.length
          (resultCode, failedCount, failedCount.asColumnOf[Double] / totalDevices)
      }
      .result
      .map(_.map(stat => FailedStat(stat._1, stat._2, stat._3)))
  }

  def calculateFailedEcuStats(correlationId: CorrelationId)(implicit ec: ExecutionContext): DBIO[Seq[FailedStat]] = {
    val totalDevices = ecuUpdateReports
      .filter(_.correlationId === correlationId)
      .length
      .asColumnOf[Double]

    ecuUpdateReports
      .filter(_.correlationId === correlationId)
      .filter(_.resultCode > 1)
      .groupBy(_.resultCode)
      .map {
        case (resultCode, failedDevices) =>
          val failedCount = failedDevices.length
          (resultCode, failedCount, failedCount.asColumnOf[Double] / totalDevices)
      }
      .result
      .map(_.map(stat => FailedStat(stat._1, stat._2, stat._3)))
  }

}
