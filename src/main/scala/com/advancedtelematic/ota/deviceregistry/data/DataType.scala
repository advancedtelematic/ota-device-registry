package com.advancedtelematic.ota.deviceregistry.data

import java.util.UUID

import cats.Show
import com.advancedtelematic.libats.data.UUIDKey.{UUIDKey, UUIDKeyObj}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event}
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType

object DataType {
  case class IndexedEvent(device: DeviceId, eventID: String, eventType: IndexedEventType, correlationId: Option[CorrelationId])

  case class CorrelationId(uuid: UUID) extends UUIDKey
  object CorrelationId extends UUIDKeyObj[CorrelationId]

  object IndexedEventType extends Enumeration {
    type IndexedEventType = Value

    val DownloadComplete, InstallationComplete = Value
  }

  implicit val eventShow: Show[Event] = Show { event =>
    s"(device=${event.deviceUuid},eventId=${event.eventId},eventType=${event.eventType})"
  }
}
