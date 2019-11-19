package com.advancedtelematic.ota.api_provider.http

import com.advancedtelematic.libats.data.DataType.{HashMethod, Namespace}
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientHashes, ClientTargetItem}
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import com.advancedtelematic.ota.api_provider.data.DataType.{ApiDevice, InstalledTarget, ListingDevice, PrimaryEcu}
import com.advancedtelematic.ota.deviceregistry.data.DataType.SearchParams
import com.advancedtelematic.ota.deviceregistry.data.Device
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class ApiProvider(directorClient: DirectorClient)(implicit ec: ExecutionContext, db: Database) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  implicit class DeviceToApiTranslation(device: Device) {
    def toApi(primaryEcu: Option[PrimaryEcu]): ApiDevice =
      ApiDevice(device.deviceId, device.uuid, device.deviceName, device.lastSeen, device.deviceStatus, primaryEcu: Option[PrimaryEcu])
  }

  def findDevice(ns: Namespace, deviceId: DeviceId): Future[ApiDevice] = for {
   localInfo <- db.run(DeviceRepository.findByUuid(deviceId))
   directorInfo <- directorClient.fetchDeviceEcus(ns, deviceId)
  } yield {
    val primaryEcuInfo = directorInfo.find(_.primary).map { ecuInfo =>
      val hashes = Map(HashMethod.SHA256 -> ecuInfo.image.hash.sha256)
      PrimaryEcu(ecuInfo.id, InstalledTarget(ecuInfo.image.filepath, hashes, ecuInfo.image.size))
    }

    if(primaryEcuInfo.isEmpty) {
      _log.warn(s"Could not get primary ecu for $deviceId from director")
    }

    localInfo.toApi(primaryEcuInfo)
  }

  def allDevices(ns: Namespace, oemId: Option[DeviceOemId], limit: Option[Long], offset: Option[Long]): Future[PaginationResult[ListingDevice]] = {
    val f = db.run(DeviceRepository.search(ns, SearchParams.all(limit, offset).copy(oemId = oemId)))
    f.map(_.map { device =>
      ListingDevice(device.uuid, device.deviceId)
    })
  }
}
