package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import cats.instances.option._
import cats.syntax.apply._
import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.ecuReplacedCodec
import com.advancedtelematic.libats.messaging_datatype.Messages.{EcuAndHardwareId, EcuReplaced}
import com.advancedtelematic.libats.slick.codecs.SlickRefined.refinedMappedType
import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
import com.advancedtelematic.libats.slick.db.SlickResultExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey.dbMapping
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, ValidHardwareIdentifier}
import com.advancedtelematic.ota.deviceregistry.common.Errors
import eu.timepit.refined.refineV
import io.circe.Json
import io.circe.syntax._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object EcuReplacementRepository {

  class EcuReplacementTable(tag: Tag) extends Table[EcuReplaced](tag, "EcuReplacement") {
    def deviceId = column[DeviceId]("device_uuid")
    def formerEcuId = column[EcuIdentifier]("former_ecu_id")
    def formerHardwareId = column[HardwareIdentifier]("former_hardware_id")
    def currentEcuId = column[EcuIdentifier]("current_ecu_id")
    def currentHardwareId = column[HardwareIdentifier]("current_hardware_id")
    def replacedAt = column[Instant]("replaced_at")

    override def * =
      (deviceId, formerEcuId, formerHardwareId, currentEcuId, currentHardwareId, replacedAt) <> (
        { case (did, fe, fh, ce, ch, w) =>
          EcuReplaced(did, EcuAndHardwareId(fe, fh.value), EcuAndHardwareId(ce, ch.value), w)
        },
        (er: EcuReplaced) =>
          (
            refineV[ValidHardwareIdentifier](er.former.hardwareId).toOption,
            refineV[ValidHardwareIdentifier](er.current.hardwareId).toOption
          ).mapN { case (fhid, chid) => (er.deviceUuid, er.former.ecuId, fhid, er.current.ecuId, chid, er.eventTime) }
      )

  }

  private val ecuReplacements = TableQuery[EcuReplacementTable]

  def insert(ecuReplaced: EcuReplaced)(implicit ec: ExecutionContext): DBIO[Unit] =
    (ecuReplacements += ecuReplaced)
      .handleForeignKeyError(Errors.MissingDevice)
      // This is redundant because director won't allow it, but let's not rely on that.
      .handleIntegrityErrors(Errors.EcuRepeatedReplacement(ecuReplaced.deviceUuid, ecuReplaced.former.ecuId))
      .map(_ => ())

  private def fetchForDevice(deviceId: DeviceId)(implicit ec: ExecutionContext): DBIO[Seq[EcuReplaced]] =
    ecuReplacements
      .filter(_.deviceId === deviceId)
      .result

  def deviceHistory(deviceId: DeviceId, offset: Long, limit: Long)(implicit ec: ExecutionContext): DBIO[PaginationResult[Json]] =
    for {
      installations <- InstallationReportRepository.queryInstallationHistory(deviceId).result
      replacements <- fetchForDevice(deviceId).map(_.map(_.asJson))
      history = (installations ++ replacements).sortBy(_.hcursor.get[Instant]("eventTime").toOption)(Ordering[Option[Instant]].reverse)
      values = history.drop(offset.toInt).take(limit.toInt)
    } yield PaginationResult(values, history.length, offset, limit)
}
