/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import org.scalatest.time.SpanSugar._
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import cats.syntax.option._
import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.messaging_datatype.DataType.EventType
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceEventListener
import com.advancedtelematic.ota.deviceregistry.EventJournalSpec.EventPayload
import io.circe.{Decoder, Json}
import io.circe.testing.ArbitraryInstances
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, DeviceT}
import io.circe.generic.semiauto._

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
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  import EventJournalSpec.EventPayloadEncoder

  private[this] val InstantGen: Gen[Instant] = Gen
    .chooseNum(0, 2 * 365 * 24 * 60)
    .map(x => Instant.now.minus(x, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS))

  private[this] val EventTypeGen: Gen[EventType] =
    for {
      id  <- Gen.oneOf("DownloadComplete", "InstallationComplete")
      ver <- Gen.chooseNum(1, 10)
    } yield EventType(id, ver)

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
    correlationId <- Gen.uuid.map(uuid => CorrelationId(s"ota:update:uuid:$uuid"))
    json = Json.obj("correlationId" -> correlationId.asJson)
  } yield event.copy(event = json, eventType = EventType("InstallationComplete", 0)) -> correlationId

  implicit val ArbitraryEvents = Arbitrary(EventsGen)

  new DeviceEventListener(system.settings.config, db, MetricsSupport.metricRegistry).start()

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  property("events can be recorded in journal and retrieved") {
    forAll { (device: DeviceT, events: List[EventPayload]) =>
      val deviceUuid = createDeviceOk(device)

      recordEvents(deviceUuid, events.asJson) ~> route ~> check {
        status shouldBe StatusCodes.NoContent
      }

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

    recordEvents(deviceUuid, List(event0, event1).asJson) ~> route ~> check {
      status shouldBe StatusCodes.NoContent
    }

    eventually(timeout(3.seconds), interval(100.millis)) {
      getEvents(deviceUuid, correlationId0.some) ~> route ~> check {
        status should equal(StatusCodes.OK)

        val events = responseAs[List[EventPayload]].map(_.id)

        events should contain(event0.id)
        events shouldNot contain(event1.id)
      }
    }
  }
}
