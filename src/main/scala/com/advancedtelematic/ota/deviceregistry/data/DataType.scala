package com.advancedtelematic.ota.deviceregistry.data
import cats.Show
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, Event, UpdateId}
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import com.advancedtelematic.ota.deviceregistry.data.Device.{DeviceName, DeviceOemId, DeviceType}
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex

object DataType {
  case class IndexedEvent(device: DeviceId, eventID: String, eventType: IndexedEventType, correlationId: Option[CorrelationId])

  case class FailedStat(resultCode: Int, total: Int, percentage: Double)

  case class CorrelationId(id: String) extends AnyVal
  object CorrelationId {
    val namespace = "here-ota"
    def make(resource: String, id: String) = CorrelationId(s"$namespace:$resource:$id")
    def from(id: UpdateId): CorrelationId = make("mtus", id.uuid.toString)
  }
  object IndexedEventType extends Enumeration {
    type IndexedEventType = Value

    val DownloadComplete, InstallationComplete = Value
  }

  final case class DeviceT(deviceName: DeviceName,
                           deviceId: DeviceOemId,
                           deviceType: DeviceType = DeviceType.Other,
                           credentials: Option[String] = None,
                           credentialsType: Option[CredentialsType] = None)

  final case class UpdateDevice(deviceName: DeviceName)

  implicit val eventShow: Show[Event] = Show { event =>
    s"(device=${event.deviceUuid},eventId=${event.eventId},eventType=${event.eventType})"
  }

  final case class DeviceReport(deviceId: DeviceId, correlationId: CorrelationId, resultCode: Int)
  final case class EcuReport(ecuSerial: EcuSerial, correlationId: CorrelationId, resultCode: Int)

  final case class SearchParams(oemId: Option[DeviceOemId], grouped: Option[Boolean], groupType: Option[GroupType],
                          groupId: Option[GroupId], regex: Option[String Refined Regex], offset: Option[Long], limit: Option[Long]) {
    if (oemId.isDefined) {
      require(groupId.isEmpty, "Invalid parameters: groupId must be empty when searching by deviceId.")
      require(regex.isEmpty, "Invalid parameters: regex must be empty when searching by deviceId.")
    }
  }
}
