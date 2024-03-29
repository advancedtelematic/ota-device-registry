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

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import akka.http.scaladsl.model.StatusCodes
import cats.syntax.option._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.{CorrelationCampaignId, CorrelationId, MultiTargetUpdateId}
import com.advancedtelematic.libats.data.{Limit, Offset}
import com.advancedtelematic.libats.messaging_datatype.DataType.{Event, EventType}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeleteDeviceRequest, DeviceEventMessage}
import com.advancedtelematic.ota.deviceregistry.ResourcePropSpec
import com.advancedtelematic.ota.deviceregistry.db.EventJournalSpec.EventPayload
import com.advancedtelematic.ota.deviceregistry.daemon.{DeleteDeviceListener, DeviceEventListener}
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import io.circe.generic.semiauto._
import io.circe.testing.ArbitraryInstances
import io.circe.{Decoder, Json}
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.SpanSugar._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

object EventJournalSpec {
  private[EventJournalSpec] final case class EventPayload(id: UUID,
                                                          deviceTime: Instant,
                                                          eventType: EventType,
                                                          event: Json)

  private[EventJournalSpec] implicit val EventPayloadEncoder = deriveEncoder[EventPayload]

  private[EventJournalSpec] implicit val EventPayloadFromResponse: Decoder[EventPayload] =
    Decoder.instance { c =>
      for {
        id         <- c.get[String]("eventId").map(UUID.fromString)
        deviceTime <- c.get[Instant]("deviceTime")
        eventType  <- c.get[EventType]("eventType")
        payload    <- c.get[Json]("payload")
      } yield EventPayload(id, deviceTime, eventType, payload)
    }
}

class EventJournalSpec extends ResourcePropSpec with ScalaFutures with Eventually with ArbitraryInstances {
  import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
  import io.circe.syntax._

  private[this] val InstantGen: Gen[Instant] = Gen
    .chooseNum(0, 2 * 365 * 24 * 60)
    .map(x => Instant.now.minus(x, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS))

  private[this] val EventTypeGen: Gen[EventType] =
    for {
      id  <- Gen.oneOf("DownloadComplete", "InstallationComplete")
      ver <- Gen.chooseNum(1, 10)
    } yield EventType(id, ver)

  val genCorrelationId: Gen[CorrelationId] =
    Gen.uuid.flatMap(uuid => Gen.oneOf(CorrelationCampaignId(uuid), MultiTargetUpdateId(uuid)))

  implicit val EventGen = for {
    id        <- Gen.uuid
    timestamp <- InstantGen
    eventType <- EventTypeGen
    json      <- arbitraryJsonObject.arbitrary
  } yield EventPayload(id, timestamp, eventType, json.asJson)

  implicit val EventsGen: Gen[List[EventPayload]] =
    Gen.chooseNum(1, 10).flatMap(Gen.listOfN(_, EventGen))

  val installCompleteEventGen: Gen[(EventPayload, CorrelationId)] = for {
    event <- EventGen
    correlationId <- genCorrelationId
    json = Json.obj("correlationId" -> correlationId.asJson)
  } yield event.copy(event = json, eventType = EventType("InstallationComplete", 0)) -> correlationId

  val event1Gen: Gen[EventPayload] = for {
    event <- EventGen
  } yield event.copy(eventType = EventType("Event1", 0))

  val event2Gen: Gen[EventPayload] = for {
    event <- EventGen
  } yield event.copy(eventType = EventType("Event2", 0))

  implicit val ArbitraryEvents = Arbitrary(EventsGen)

  val listener = new DeviceEventListener()

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  property("events can be recorded in journal and retrieved") {
    forAll { (device: DeviceT, events: List[EventPayload]) =>
      val deviceUuid = createDeviceOk(device)

      events
        .map(ep => Event(deviceUuid, ep.id.toString, ep.eventType, ep.deviceTime, Instant.now, ep.event))
        .map(DeviceEventMessage(defaultNs, _))
        .map(listener.apply)


      eventually(timeout(5.seconds), interval(100.millis)) {
        getEvents(deviceUuid) ~> route ~> check {
          status should equal(StatusCodes.OK)
          val messages = responseAs[List[EventPayload]]

          messages.length should equal(events.length)
          messages should contain theSameElementsAs events
        }
      }
    }
  }

  property("indexes an event by type") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val (event0, correlationId0) = installCompleteEventGen.generate
    val event1 = EventGen.retryUntil(_.eventType.id != "InstallationComplete").generate

    List(event0, event1)
      .map(ep => Event(deviceUuid, ep.id.toString, ep.eventType, ep.deviceTime, Instant.now, ep.event))
      .map(DeviceEventMessage(defaultNs, _))
      .map(listener.apply)

    eventually(timeout(3.seconds), interval(100.millis)) {
      getEvents(deviceUuid, correlationId0.some) ~> route ~> check {
        status should equal(StatusCodes.OK)

        val events = responseAs[List[EventPayload]].map(_.id)

        events should contain(event0.id)
        events shouldNot contain(event1.id)
      }
    }
  }

  property("does not return events that do not match on correlation id") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val (event0, _) = installCompleteEventGen.generate
    val event1 = EventGen.retryUntil(_.eventType.id != "InstallationComplete").generate

    List(event0, event1)
      .map(ep => Event(deviceUuid, ep.id.toString, ep.eventType, ep.deviceTime, Instant.now, ep.event))
      .map(DeviceEventMessage(defaultNs, _))
      .map(listener.apply)

    eventually(timeout(3.seconds), interval(100.millis)) {
      getEvents(deviceUuid, CorrelationCampaignId(UUID.randomUUID()).some) ~> route ~> check {
        status should equal(StatusCodes.OK)
        val events = responseAs[List[EventPayload]]
        events shouldBe empty
      }
    }
  }

  property("DELETE device archives its indexed events") {
    val uuid = createDeviceOk(genDeviceT.generate)
    val (e, _) = installCompleteEventGen.generate
    val event = Event(uuid, e.id.toString, e.eventType, e.deviceTime, Instant.now, e.event)
    val deviceEventMessage = DeviceEventMessage(defaultNs, event)
    val journal = new EventJournal()

    listener.apply(deviceEventMessage).futureValue
    new DeleteDeviceListener().apply(DeleteDeviceRequest(defaultNs, uuid))

    eventually(timeout(5.seconds), interval(100.millis)) {
      journal.getIndexedEvents(uuid, correlationId = None, Offset(0), Limit(10), None).futureValue shouldBe empty
      journal.getArchivedIndexedEvents(uuid).futureValue.map(_.eventID) shouldBe Seq(event.eventId)
    }
  }

  property("should return only events of specified type") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val event1 = event1Gen.generate
    val event2 = event2Gen.generate

    List(event1, event2)
      .map(ep => Event(deviceUuid, ep.id.toString, ep.eventType, ep.deviceTime, Instant.now, ep.event))
      .map(DeviceEventMessage(defaultNs, _))
      .map(listener.apply)

    eventually(timeout(3.seconds), interval(100.millis)) {
      getEvents(deviceUuid, eventTypes = Some("Event1")) ~> route ~> check {
        status should equal(StatusCodes.OK)
        val events = responseAs[List[EventPayload]].map(_.id)
        events should contain(event1.id)
        events shouldNot contain(event2.id)
      }
    }
  }
}
