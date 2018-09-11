/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import com.advancedtelematic.libats.messaging_datatype.DataType.{Event, EventType}
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import com.advancedtelematic.libats.slick.db.SlickExtensions.javaInstantMapping
import io.circe.Json
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId => DeviceUUID}
import eu.timepit.refined.refineV

import scala.concurrent.{ExecutionContext, Future}

object EventJournal {
  private implicit val JsonType = MappedColumnType.base[Json, String](
    x => x.noSpaces,
    io.circe.parser.parse(_).getOrElse(throw new IllegalStateException("Data in DB is not a valid Json"))
  )
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
      (deviceUuid, eventId, eventTypeId, eventTypeVersion, deviceTime, receivedAt, event).shaped <> (toEvent _, fromEvent _)
  }

  private[EventJournal] val events = TableQuery[EventJournalTable]

  def deleteEvents(deviceUuid: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    events.filter(_.deviceUuid === deviceUuid).delete
}

class EventJournal(db: Database) {
  import EventJournal.events

  def recordEvent(event: Event): Future[Int] = db.run(events.insertOrUpdate(event))

  def getEvents(deviceUuid: Uuid): Future[Seq[Event]] = db.run(events.filter(_.deviceUuid === deviceUuid).result)
}
