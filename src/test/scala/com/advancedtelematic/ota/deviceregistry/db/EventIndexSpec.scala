package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import cats.syntax.option._
import com.advancedtelematic.libats.data.DataType.{CampaignId, CorrelationId, MultiTargetUpdateId}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event, EventType, UpdateId}
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.DataType.{IndexedEvent, IndexedEventType}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import io.circe.Json
import io.circe.syntax._
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{EitherValues, FunSuite, Matchers}

class EventIndexSpec extends FunSuite with ScalaFutures with DatabaseSpec with Matchers with EitherValues {

  val genCorrelationId: Gen[CorrelationId] =
    Gen.uuid.flatMap(uuid => Gen.oneOf(CampaignId(uuid), MultiTargetUpdateId(uuid)))

  val downloadCompleteEventGen: Gen[Event] = for {
    device <- Gen.uuid.map(DeviceId.apply)
    eventId <- Gen.uuid.map(_.toString)
    eventTypeStr = "DownloadComplete"
    eventType = EventType(eventTypeStr, 0)
    json = Json.obj()
  } yield Event(device, eventId, eventType, Instant.now, Instant.now, json)

  val installCompleteEventGen: Gen[(Event, CorrelationId)] = for {
    event <- downloadCompleteEventGen
    correlationId <- genCorrelationId
    json = Json.obj("correlationId" -> correlationId.asJson)
  } yield event.copy(eventType = EventType("InstallationComplete", 0), payload = json) -> correlationId

  test("indexes a InstallationComplete event by type") {
    val (event, correlationId) = installCompleteEventGen.generate

    val indexedEvent = EventIndex.index(event).right.value

    indexedEvent shouldBe IndexedEvent(event.deviceUuid, event.eventId, IndexedEventType.InstallationComplete, correlationId.some)
  }

  test("indexes a DownloadComplete event by type") {
    val event = downloadCompleteEventGen.generate

    val indexedEvent = EventIndex.index(event).right.value

    indexedEvent shouldBe IndexedEvent(event.deviceUuid, event.eventId, IndexedEventType.DownloadComplete, None)
  }

  test("does not index event if it cannot be parsed") {
    val event = downloadCompleteEventGen.generate.copy(eventType = EventType("DownloadComplete", 20))

    val indexedEvent = EventIndex.index(event).left.value

    indexedEvent shouldBe "Unknown event type EventType(DownloadComplete,20)"
  }
}
