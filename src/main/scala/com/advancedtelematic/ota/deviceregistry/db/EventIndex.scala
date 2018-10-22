package com.advancedtelematic.ota.deviceregistry.db

import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.circe.CirceInstances._
import com.advancedtelematic.libats.codecs.CirceRefined._
import com.advancedtelematic.libats.messaging_datatype.DataType.{EcuSerial, Event, EventType}
import com.advancedtelematic.ota.deviceregistry.data.DataType.EventField._
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType._
import com.advancedtelematic.ota.deviceregistry.data.DataType._
import io.circe.DecodingFailure

object EventIndex {
  type EventIndexResult = Either[String, IndexedEvent]

  private def toErrorString(event: Event)(df: DecodingFailure): String =
    s"Could not parse payload for event ${event.show}: $df"

  private def parseDownloadComplete: PartialFunction[Event, EventIndexResult] = {
    case event if event.eventType == EventType("DownloadComplete", 0) =>
      IndexedEvent(event.deviceUuid, event.eventId, DownloadComplete, None, None, None).asRight
  }

  private def parseInstallationComplete: PartialFunction[Event, EventIndexResult] = {
    case event if event.eventType == EventType("InstallationComplete", 0) =>
      event.payload.hcursor.get[CorrelationId](CORRELATION_ID.toString)
      .map(cid => IndexedEvent(event.deviceUuid, event.eventId, InstallationComplete, cid.some, None, None))
      .leftMap(toErrorString(event))
  }

  private def parseInstallationReport: PartialFunction[Event, EventIndexResult] = {
    case event if event.eventType == EventType("InstallationReport", 0) =>
      val cursor = event.payload.hcursor
      val indexedEvent = for {
        cid <- cursor.get[CorrelationId](CORRELATION_ID.toString)
        rc  <- cursor.get[Int](RESULT_CODE.toString)
      } yield IndexedEvent(event.deviceUuid, event.eventId, InstallationReport, cid.some, None, rc.some)
      indexedEvent.leftMap(toErrorString(event))
  }

  private def parseEcuInstallationReport: PartialFunction[Event, EventIndexResult] = {
    case event if event.eventType == EventType("EcuInstallationReport", 0) =>
      val cursor = event.payload.hcursor
      val indexedEvent = for {
        cid <- cursor.get[CorrelationId](CORRELATION_ID.toString)
        rc  <- cursor.get[Int](RESULT_CODE.toString)
        ecu <- cursor.get[EcuSerial](ECU_SERIAL.toString)
      } yield IndexedEvent(event.deviceUuid, event.eventId, EcuInstallationReport, cid.some, ecu.some, rc.some)
      indexedEvent.leftMap(toErrorString(event))
  }

  def index(event: Event): EventIndexResult =  {
    val parseFns =
      List(parseDownloadComplete, parseInstallationComplete, parseInstallationReport, parseEcuInstallationReport)
        .foldRight(PartialFunction.empty[Event, EventIndexResult])(_ orElse _)

    if (parseFns.isDefinedAt(event)) parseFns.apply(event)
    else s"Unknown event type ${event.eventType}".asLeft
  }

}
