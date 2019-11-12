package com.advancedtelematic.ota.api_translator.http

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.api_translator.data.DataType.ApiDevice
import com.advancedtelematic.ota.deviceregistry.data.DataType.SearchParams
import com.advancedtelematic.ota.deviceregistry.data.Device
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class ApiTranslation()(implicit ec: ExecutionContext, db: Database) {

  implicit class DeviceToApiTranslation(device: Device) {
    def toApi: ApiDevice =
      ApiDevice(device.deviceId, device.uuid, device.deviceName, device.lastSeen, device.deviceStatus)
  }

  def findDevice(deviceId: DeviceId): Future[ApiDevice] = {
    val f = db.run(DeviceRepository.findByUuid(deviceId))
    f.map(_.toApi)
  }

  def allDevices(ns: Namespace, limit: Option[Long], offset: Option[Long]): Future[PaginationResult[ApiDevice]] = {
    val f = db.run(DeviceRepository.search(ns, SearchParams.all(limit, offset)))
    f.map(_.map(_.toApi))
  }
}
