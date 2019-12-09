package com.advancedtelematic.ota.api_provider.http

import cats.syntax.option._
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import com.advancedtelematic.ota.api_provider.data.DataType.{ApiDevice, ApiDeviceEvent, ApiDeviceEvents, ApiDeviceUpdateEventName, ListingDevice, PrimaryEcu, QueueItem, SoftwareVersion}
import com.advancedtelematic.ota.deviceregistry.data.DataType.SearchParams
import com.advancedtelematic.ota.deviceregistry.data.Device
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, EventJournal}
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}


class ApiProvider(directorClient: DirectorClient, eventJournal: EventJournal)(implicit ec: ExecutionContext, db: Database) {
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
      PrimaryEcu(ecuInfo.id, SoftwareVersion(ecuInfo.image.filepath, ecuInfo.image.hash.toClient, ecuInfo.image.size))
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

  def deviceQueue(ns: Namespace, deviceId: DeviceId): Future[Vector[QueueItem]] = {
    directorClient.fetchDeviceQueue(ns, deviceId).map { directorQueueItems =>
      directorQueueItems.map { queueItem =>
        val updates = queueItem.targets.mapValues { ecuInfoImage =>
          SoftwareVersion(ecuInfoImage.filepath, ecuInfoImage.hash.toClient, ecuInfoImage.size)
        }

        queueItem.correlationId match {
          case Some(cid) =>
            QueueItem(deviceId, cid, updates).some
          case None =>
            _log.warn(s"Received empty correlationId for update for $deviceId, ignoring update")
            None
        }

      }.toVector.flatten.distinct
    }
  }

  def findUpdateEvents(namespace: Namespace, deviceId: DeviceId, correlationId: Option[CorrelationId]): Future[ApiDeviceEvents] = async {
    val indexedEvents = await(eventJournal.getIndexedEvents(deviceId, correlationId))

    val events = indexedEvents.toVector.map { case (event, indexedEvent) =>
      val ecuO = event.payload.hcursor.downField("ecu").as[EcuIdentifier].toOption
      ApiDeviceEvent(ecuO, indexedEvent.correlationId, indexedEvent.eventType, event.receivedAt, event.deviceTime)
    }

    ApiDeviceEvents(deviceId, events)
  }
}
