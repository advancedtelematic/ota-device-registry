/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import com.advancedtelematic.libats.slick.db.SlickExtensions.MappedColumnExtensions
import com.advancedtelematic.libats.messaging_datatype.DataType.{Event, EventType, DeviceId => DeviceUUID}
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import io.circe.Json
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, IndexedEvent}
import eu.timepit.refined.refineV
import SlickMappings._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import org.slf4j.LoggerFactory
import cats.syntax.show._
import com.advancedtelematic.ota.deviceregistry.data.DataType._
import com.advancedtelematic.libats.slick.db.SlickCirceMapper._

import scala.concurrent.{ExecutionContext, Future}

object EventJournal {
  class EventJournalTable(tag: Tag) extends Table[Event](tag, "EventJournal") {
    def deviceUuid       = column[Uuid]("device_uuid")
    def eventId          = column[String]("event_id")
    def deviceTime       = column[Instant]("device_time")
    def eventTypeId      = column[String]("event_type_id")
    def eventTypeVersion = column[Int]("event_type_version")
    def event            = column[Json]("event")
    def receivedAt       = column[Instant]("received_at")

    def pk = primaryKey("events_pk", (deviceUuid, eventId))

    private def fromEvent(e: Event) =
      Some((Uuid(refineV[Uuid.Valid](e.deviceUuid.uuid.toString).right.get),
           e.eventId,
           e.eventType.id,
           e.eventType.version,
           e.deviceTime,
           e.receivedAt,
           e.payload))

    private def toEvent(x: (Uuid, String, String, Int, Instant, Instant, Json)): Event =
      Event(DeviceUUID(x._1.toJava), x._2, EventType(x._3, x._4), x._5, x._6, x._7)

    override def * =
      (deviceUuid, eventId, eventTypeId, eventTypeVersion, deviceTime, receivedAt, event).shaped <> (toEvent, fromEvent)
  }

  protected [db] val events = TableQuery[EventJournalTable]

  class IndexedEventTable(tag: Tag) extends Table[IndexedEvent](tag, "IndexedEvents") {
    def deviceUuid = column[DeviceUUID]("device_uuid")
    def eventId = column[String]("event_id")
    def eventType = column[IndexedEventType]("event_type")
    def correlationId = column[Option[CorrelationId]]("correlation_id")

    def pk = primaryKey("indexed_event_pk", (deviceUuid, eventId))

    def * = (deviceUuid, eventId, eventType, correlationId).shaped <> ((IndexedEvent.apply _).tupled, IndexedEvent.unapply)
  }

  protected [db] val indexedEvents = TableQuery[IndexedEventTable]

  def deleteEvents(deviceUuid: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
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

  def getEvents(deviceUuid: Uuid, correlationId: Option[CorrelationId]): Future[Seq[Event]] =
    if(correlationId.isDefined)
      db.run(findEventsByCorrelationId(deviceUuid, correlationId.get))
    else
      db.run(events.filter(_.deviceUuid === deviceUuid).result)


  // TODO:SM MappedTo, use DeviceId in Device instead
  protected [db] def findEventsByCorrelationId(deviceUuid: Uuid, correlationId: CorrelationId): DBIO[Seq[Event]] = {
    EventJournal.events
      .filter(_.deviceUuid === deviceUuid)
      .join(EventJournal.indexedEvents)
      .on { case (ej, ie) => ej.deviceUuid.mappedTo[String] === ie.deviceUuid.mappedTo[String] && ej.eventId === ie.eventId }
      .map(_._1)
      .result
  }
}
