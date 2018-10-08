package com.advancedtelematic.ota.deviceregistry.db

import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.libats.messaging_datatype.DataType.{Event, EventType}
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, IndexedEvent, _}
import com.advancedtelematic.circe.CirceInstances._

object EventIndex {
  type EventIndexResult = Either[String, IndexedEvent]

  private def parseInstallationComplete(event: Event): EventIndexResult = {
    event.payload.hcursor.downField("correlationId").as[CorrelationId]
      .leftMap(err => s"Could not parse payload for event ${event.show}: $err")
      .map { correlationId =>
        IndexedEvent(event.deviceUuid, event.eventId, IndexedEventType.InstallationComplete , correlationId.some)
      }
  }

  private def parseDownloadComplete(event: Event): EventIndexResult =
    IndexedEvent(event.deviceUuid, event.eventId, IndexedEventType.DownloadComplete, None).asRight

  def index(event: Event): EventIndexResult = event.eventType match {
    case EventType("DownloadComplete", 0) =>
      parseDownloadComplete(event)
    case EventType("InstallationComplete", 0) =>
      parseInstallationComplete(event)
    case eventType =>
      s"Unknown event type $eventType".asLeft
  }
}
