package com.advancedtelematic.ota.deviceregistry.data
import cats.Show
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event}
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import com.advancedtelematic.ota.deviceregistry.data.Device.{DeviceName, DeviceOemId, DeviceType}

object DataType {
  case class IndexedEvent(device: DeviceId, eventID: String, eventType: IndexedEventType, correlationId: Option[CorrelationId])

  case class CorrelationId(id: String) extends AnyVal

  object IndexedEventType extends Enumeration {
    type IndexedEventType = Value

    val DownloadComplete, InstallationComplete = Value
  }

  final case class DeviceT(deviceName: DeviceName,
                           deviceId: DeviceOemId,
                           deviceType: DeviceType = DeviceType.Other,
                           credentials: Option[String] = None,
                           credentialsType: Option[CredentialsType] = None)

  implicit val eventShow: Show[Event] = Show { event =>
    s"(device=${event.deviceUuid},eventId=${event.eventId},eventType=${event.eventType})"
  }
}
