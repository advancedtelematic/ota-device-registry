/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry.db

import akka.actor.Scheduler

import java.time.Instant
import cats.syntax.show._
import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.libats.data.{Limit, Offset, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event, EventType}
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.libats.slick.db.SlickCirceMapper._
import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickUrnMapper.correlationIdMapper
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import com.advancedtelematic.ota.deviceregistry.data.DataType.{IndexedEvent, _}
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import io.circe.Json
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.slick.db.SlickExtensions._

import scala.concurrent.{ExecutionContext, Future}

object EventJournal {
  class EventJournalTable(tag: Tag) extends Table[Event](tag, "EventJournal") {
    def deviceUuid       = column[DeviceId]("device_uuid")
    def eventId          = column[String]("event_id")
    def deviceTime       = column[Instant]("device_time")
    def eventTypeId      = column[String]("event_type_id")
    def eventTypeVersion = column[Int]("event_type_version")
    def event            = column[Json]("event")
    def receivedAt       = column[Instant]("received_at")

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
    def createdAt = column[Instant]("created_at")

    def pk = primaryKey("indexed_event_pk", (deviceUuid, eventId))

    def * = (deviceUuid, eventId, eventType, correlationId).shaped <> (IndexedEvent.tupled, IndexedEvent.unapply)
  }

  protected [db] val indexedEvents = TableQuery[IndexedEventTable]

  class IndexedEventArchiveTable(tag: Tag) extends Table[IndexedEvent](tag, "IndexedEventsArchive") {
    def deviceUuid    = column[DeviceId]("device_uuid")
    def eventId       = column[String]("event_id")
    def eventType     = column[IndexedEventType]("event_type")
    def correlationId = column[Option[CorrelationId]]("correlation_id")

    def pk = primaryKey("indexed_event_pk", (deviceUuid, eventId))

    def * = (deviceUuid, eventId, eventType, correlationId).shaped <> (IndexedEvent.tupled, IndexedEvent.unapply)
  }

  protected[db] val indexedEventsArchive = TableQuery[IndexedEventArchiveTable]

  def deleteEvents(deviceUuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Int] =
    events.filter(_.deviceUuid === deviceUuid).delete

  def archiveIndexedEvents(deviceUuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Int] = {
    val q = indexedEvents.filter(_.deviceUuid === deviceUuid)
    val io = for {
      ies <- q.result
      _   <- indexedEventsArchive ++= ies
      n   <- q.delete
    } yield n
    io.transactionally
  }

}

class EventJournal()(implicit db: Database, ec: ExecutionContext, scheduler: Scheduler) {
  import EventJournal.{events, indexedEvents, indexedEventsArchive}

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

    db.runWithRetry(io).map(_ => ())
  }

  def getEvents(deviceUuid: DeviceId, correlationId: Option[CorrelationId], offset: Offset, limit: Limit, eventTypes: Option[Seq[String]]): Future[Seq[Event]] =
    if(correlationId.isDefined)
      getIndexedEvents(deviceUuid, correlationId, offset, limit, eventTypes).map(_.map(_._1))
    else
      db.runWithRetry(filterEvents(deviceUuid, eventTypes).sortBy(_.receivedAt.desc).take(limit.value).drop(offset.value).result)

  private def getIndexedEventsQuery(deviceUuid: DeviceId, correlationId: Option[CorrelationId], eventTypes: Option[Seq[String]] = None) = {
    filterEvents(deviceUuid, eventTypes)
      .join(EventJournal.indexedEvents.maybeFilter(_.correlationId === correlationId))
      .on { case (ej, ie) => ej.deviceUuid === ie.deviceUuid && ej.eventId === ie.eventId }
      .sortBy { case (ej, ie) => ej.deviceTime.desc -> ie.createdAt.desc }
  }

  def getIndexedEvents(deviceUuid: DeviceId, correlationId: Option[CorrelationId], offset: Offset, limit: Limit, eventTypes: Option[Seq[String]]): Future[Seq[(Event, IndexedEvent)]] =
    db.runWithRetry(getIndexedEventsQuery(deviceUuid, correlationId, eventTypes).take(limit.value).drop(offset.value).result)

  def getPaginatedIndexedEvents(deviceUuid: DeviceId, correlationId: Option[CorrelationId], offset: Offset, limit: Limit): Future[PaginationResult[(Event, IndexedEvent)]] =
    db.runWithRetry(getIndexedEventsQuery(deviceUuid, correlationId).paginateResult(offset, limit))

  protected [db] def getArchivedIndexedEvents(deviceUuid: DeviceId): Future[Seq[IndexedEvent]] =
    db.runWithRetry(indexedEventsArchive.filter(_.deviceUuid === deviceUuid).result)

  private def filterEvents(deviceUuid: DeviceId, eventTypes: Option[Seq[String]]) = {
    val filteredByUuid = events.filter(_.deviceUuid === deviceUuid)
    eventTypes match {
      case Some(types) => filteredByUuid.filter(_.eventTypeId.inSet(types))
      case None => filteredByUuid
    }
  }
}

