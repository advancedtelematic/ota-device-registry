package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal.stringAnyValSerializer
import com.advancedtelematic.libats.slick.db.SlickPagination._
import com.advancedtelematic.libats.slick.db.SlickResultExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey.dbMapping
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import com.advancedtelematic.ota.deviceregistry.common.Errors.MissingDevice
import com.advancedtelematic.ota.deviceregistry.data.Checksum
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CurrentSoftwareImage, EcuImage, SoftwareImage}
import com.advancedtelematic.ota.deviceregistry.db.DbOps.PaginationResultOps
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.devices
import com.advancedtelematic.ota.deviceregistry.db.EcuRepository.ecus
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object CurrentImageRepository {

  class CurrentImageTable(tag: Tag) extends Table[CurrentSoftwareImage](tag, "CurrentImage") {
    def deviceId = column[DeviceId]("device_uuid")
    def ecuId = column[EcuIdentifier]("ecu_id")
    def filepath   = column[TargetFilename]("filepath")
    def checksum   = column[Checksum]("checksum")
    def size       = column[Long]("size")

    def pk = primaryKey("pk_current_image", (deviceId, ecuId))

    type CurrentImageTableRow = (DeviceId, EcuIdentifier, TargetFilename, Checksum, Long)

    private def fromRow(row: CurrentImageTableRow) =
      CurrentSoftwareImage(row._1, row._2, SoftwareImage(row._3, row._4, row._5))
    private def toRow(i: CurrentSoftwareImage) =
      Some((i.deviceId, i.ecuId, i.image.filepath, i.image.checksum, i.image.size))

    override def * = (deviceId, ecuId, filepath, checksum, size) <> (fromRow, toRow)
  }

  protected[db] val images = TableQuery[CurrentImageTable]

  def saveSoftwareImage(deviceId: DeviceId, ecuId: EcuIdentifier, image: SoftwareImage)(implicit ec: ExecutionContext): DBIO[Unit] =
    images.insertOrUpdate(CurrentSoftwareImage(deviceId, ecuId, image)).map(_ => ())

  def listImagesForDevice(deviceId: DeviceId)(implicit ec: ExecutionContext): DBIO[Seq[CurrentSoftwareImage]] =
    images.filter(_.deviceId === deviceId).result

  def findAffected(ns: Namespace, filepath: TargetFilename, offset: Option[Long], limit: Option[Long])
                  (implicit ec: ExecutionContext): DBIO[PaginationResult[DeviceId]] =
    images
      .filter(_.filepath === filepath)
      .join(devices.filter(_.namespace === ns))
      .on(_.deviceId === _.uuid)
      .map(_._2.uuid)
      .distinct
      .paginateResult(offset.orDefaultOffset, limit.orDefaultLimit)

  def countInstalledImages(ns: Namespace, filepaths: Seq[TargetFilename])(implicit ec: ExecutionContext): DBIO[Map[TargetFilename, Int]] =
    images
      .filter(_.filepath inSet filepaths)
      .join(devices.filter(_.namespace === ns))
      .on(_.deviceId === _.uuid)
      .map(_._1)
      .groupBy(_.filepath)
      .map { case (f, r) => (f, r.length) }
      .result
      .map(_.toMap)

  def findEcuImages(ns: Namespace, deviceId: DeviceId)(implicit ec: ExecutionContext): DBIO[Seq[EcuImage]] =
    devices
      .filter(_.namespace === ns)
      .filter(_.uuid === deviceId)
      .join(images)
      .on(_.uuid === _.deviceId)
      .map(_._2)
      .join(ecus)
      .on(_.ecuId === _.ecuId)
      .result
      .failIfEmpty(MissingDevice)
      .map(_.map(t => t._1.image -> t._2))
      .map(_.map {
        case (i, e) =>
          EcuImage(e.ecuId, e.ecuType, e.primary, SoftwareImage(i.filepath, i.checksum, i.size))
      })

}
