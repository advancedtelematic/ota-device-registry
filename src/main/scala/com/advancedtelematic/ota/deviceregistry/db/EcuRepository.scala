package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
import com.advancedtelematic.libats.slick.db.SlickPagination._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TufKey}
import com.advancedtelematic.libtuf_server.data.TufSlickMappings._
import com.advancedtelematic.ota.deviceregistry.data.DataType.Ecu
import com.advancedtelematic.ota.deviceregistry.db.DbOps.PaginationResultOps
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object EcuRepository {

  class EcuTable(tag: Tag) extends Table[Ecu](tag, "Ecu") {
    def deviceUuid = column[DeviceId]("device_uuid")
    def ecuId      = column[EcuIdentifier]("ecu_id")
    def ecuType    = column[HardwareIdentifier]("ecu_type")
    def primary    = column[Boolean]("primary")
    def publicKey  = column[TufKey]("public_key")

    def createdAt = column[Instant]("created_at")

    def pk = primaryKey("ecu_pk", (deviceUuid, ecuId))

    override def * = (deviceUuid, ecuId, ecuType, primary, publicKey) <> ((Ecu.apply _).tupled, Ecu.unapply)
  }

  protected[db] val ecus = TableQuery[EcuTable]

  def listEcusForDevice(deviceUuid: DeviceId, offset: Option[Long], limit: Option[Long])
                       (implicit ec: ExecutionContext): DBIO[PaginationResult[Ecu]] = {
    ecus
      .filter(_.deviceUuid === deviceUuid)
      .paginateResult(offset.orDefaultOffset, limit.orDefaultLimit)
  }

  def delete(deviceUuid: DeviceId): DBIO[Int] = ecus.filter(_.deviceUuid === deviceUuid).delete

}
