package com.advancedtelematic.ota.deviceregistry.db

import cats.syntax.option._
import com.advancedtelematic.libats.messaging_datatype.DataType.EventType
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.DataType.EventField._
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEvent
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType._
import com.advancedtelematic.ota.deviceregistry.data.EventGenerators
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{EitherValues, FunSuite, Matchers}

class EventIndexSpec extends FunSuite with ScalaFutures with DatabaseSpec with Matchers with EitherValues with EventGenerators {

  test("indexes a DownloadComplete event by type") {
    val event = genEvent(DownloadComplete).generate

    val indexedEvent = EventIndex.index(event).right.value
    val expected = IndexedEvent(event.deviceUuid, event.eventId, DownloadComplete, None, None, None)
    indexedEvent shouldBe expected
  }

  test("indexes a InstallationComplete event by type") {
    val correlationId = genCorrelationId.generate
    val m         = Map(CORRELATION_ID -> correlationId.id)
    val event = genEvent(InstallationComplete, m).generate

    val indexedEvent = EventIndex.index(event).right.value
    val expected = IndexedEvent(event.deviceUuid, event.eventId, InstallationComplete, correlationId.some, None, None)
    indexedEvent shouldBe expected
  }

  test("indexes a InstallationReport event by type") {
    val correlationId = genCorrelationId.generate
    val resultCode               = genResultCode.generate
    val m         = Map(CORRELATION_ID -> correlationId.id, RESULT_CODE -> resultCode.toString)
    val event = genEvent(InstallationReport, m).generate

    val indexedEvent = EventIndex.index(event).right.value
    val expected = IndexedEvent(event.deviceUuid, event.eventId, InstallationReport, correlationId.some, None, resultCode.some)
      indexedEvent shouldBe expected
  }

  test("indexes a EcuInstallationReport event by type") {
    val correlationId = genCorrelationId.generate
    val resultCode               = genResultCode.generate
    val ecuSerial = genEcuSerial.generate
    val m         = Map(CORRELATION_ID -> correlationId.id, RESULT_CODE -> resultCode.toString, ECU_SERIAL -> ecuSerial.value)
    val event = genEvent(EcuInstallationReport, m).generate

    val indexedEvent = EventIndex.index(event).right.value
    val expected = IndexedEvent(event.deviceUuid, event.eventId, EcuInstallationReport, Some(correlationId), ecuSerial.some, resultCode.some)
    indexedEvent shouldBe expected
  }

  test("does not index event if it cannot be parsed") {
    val event = genEvent(DownloadComplete).generate.copy(eventType = EventType("DownloadComplete", 20))

    val indexedEvent = EventIndex.index(event).left.value
    indexedEvent shouldBe "Unknown event type EventType(DownloadComplete,20)"
  }

  test("does not index event if it misses a field") {
    val event = genEvent(InstallationComplete).generate

    val indexedEvent = EventIndex.index(event).left.value
    indexedEvent should include regex "Could not parse payload for event.*InstallationComplete"
  }
}
