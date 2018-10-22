/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import cats.syntax.show._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, Event, EventType}
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickCirceMapper._
import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, IndexedEvent, _}
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import io.circe.Json
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

object EventJournal {
  class EventJournalTable(tag: Tag) extends Table[Event](tag, "EventJournal") {
    def deviceUuid       = column[DeviceId]("device_uuid")
    def eventId          = column[String]("event_id")
    def eventTypeId      = column[String]("event_type_id")
    def eventTypeVersion = column[Int]("event_type_version")
    def deviceTime       = column[Instant]("device_time")
    def receivedAt       = column[Instant]("received_at")
    def event            = column[Json]("event")

    def pk = primaryKey("events_pk", (deviceUuid, eventId))

    private def fromEvent(e: Event) =
      Some(e.deviceUuid,
           e.eventId,
           e.eventType.id,
           e.eventType.version,
           e.deviceTime,
           e.receivedAt,
           e.payload)

    private def toEvent(x: (DeviceId, String, String, Int, Instant, Instant, Json)): Event =
      Event(x._1, x._2, EventType(x._3, x._4), x._5, x._6, x._7)

    override def * =
      (deviceUuid, eventId, eventTypeId, eventTypeVersion, deviceTime, receivedAt, event).shaped <> (toEvent, fromEvent)
  }

  protected [db] val events = TableQuery[EventJournalTable]

  class IndexedEventTable(tag: Tag) extends Table[IndexedEvent](tag, "IndexedEvents") {
    def deviceUuid = column[DeviceId]("device_uuid")
    def eventId = column[String]("event_id")
    def eventType = column[IndexedEventType]("event_type")
    def correlationId = column[Option[CorrelationId]]("correlation_id")
    def ecuSerial = column[Option[EcuSerial]]("ecu_serial")
    def resultCode = column[Option[Int]]("result_code")

    def pk = primaryKey("indexed_event_pk", (deviceUuid, eventId))

    def * = (deviceUuid, eventId, eventType, correlationId, ecuSerial, resultCode).shaped <> ((IndexedEvent.apply _).tupled, IndexedEvent.unapply)
  }

  protected [db] val indexedEvents = TableQuery[IndexedEventTable]

  def deleteEvents(deviceUuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Int] =
    events.filter(_.deviceUuid === deviceUuid).delete
}

class EventJournal()(implicit db: Database, ec: ExecutionContext) {
  import EventJournal.{events, indexedEvents}

  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def recordEvent(event: Event): Future[Unit] = {
    val indexDbio = EventIndex.index(event) match {
      case Left(err) =>
        _log.info(s"Could not index event ${event.show}: $err")
        DBIO.successful(())
      case Right(e) =>
        indexedEvents.insertOrUpdate(e)
    }

    val io = events.insertOrUpdate(event).andThen(indexDbio).transactionally

    db.run(io).map(_ => ())
  }

  private def indexedEventsQuery(deviceUuid: Option[DeviceId], correlationId: Option[CorrelationId], ecuSerial: Option[EcuSerial], resultCode: Option[Int]) =
    indexedEvents
      .maybeFilter(_.deviceUuid === deviceUuid)
      .filter(r => r.correlationId === correlationId.bind || r.ecuSerial === ecuSerial.bind || r.resultCode === resultCode.bind)
      .maybeFilter(_.correlationId === correlationId)
      .maybeFilter(_.ecuSerial === ecuSerial)
      .maybeFilter(_.resultCode === resultCode)
      .join(events)
      .on{ case (ie, ej) => ie.deviceUuid === ej.deviceUuid && ie.eventId === ej.eventId }
      .map(_._2)

  def getEvents(deviceUuid: Option[DeviceId], correlationId: Option[CorrelationId], ecuSerial: Option[EcuSerial], resultCode: Option[Int]): Future[Seq[Event]] = {
    val useIndex = Seq(correlationId, ecuSerial, resultCode).exists(_.isDefined)
    val query =
      if (useIndex) indexedEventsQuery(deviceUuid, correlationId, ecuSerial, resultCode)
      else events.maybeFilter(_.deviceUuid === deviceUuid)
    db.run(query.result)
  }
}

