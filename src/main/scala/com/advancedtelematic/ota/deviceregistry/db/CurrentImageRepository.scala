package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import com.advancedtelematic.ota.deviceregistry.data.Checksum
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CurrentSoftwareImage, SoftwareImage}
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

}
