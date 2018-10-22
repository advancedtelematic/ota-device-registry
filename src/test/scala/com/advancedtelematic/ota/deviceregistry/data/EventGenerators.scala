package com.advancedtelematic.ota.deviceregistry.data
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, Event, EventType}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.EventField._
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType._
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, EventField, IndexedEventType}
import com.advancedtelematic.ota.deviceregistry.data.EventGenerators.EventPayload
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import eu.timepit.refined.api.Refined
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Decoder, Json, ObjectEncoder}
import org.scalacheck.{Arbitrary, Gen}

trait EventGenerators {

  private val genInstant: Gen[Instant] = Gen
    .chooseNum(0, 2 * 365 * 24 * 60)
    .map(x => Instant.now.minus(x, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS))

  val genCorrelationId: Gen[CorrelationId] =
    Gen.uuid.map(uuid => CorrelationId(s"here-ota:mtus:$uuid"))

  val genEcuSerial: Gen[EcuSerial] =
    Gen.choose(10, 64).flatMap(Gen.containerOfN[List, Char](_, Gen.alphaChar).map(_.mkString)).map(Refined.unsafeApply)

  val genResultCode: Gen[Int] = Gen.choose(0, 1000)

  implicit val genEvent: Gen[EventPayload] =
    Gen.oneOf(IndexedEventType.values.toList).flatMap(genEventPayload).flatMap(_._1)

  implicit val genEvents: Gen[List[EventPayload]]             = Gen.chooseNum(1, 10).flatMap(Gen.listOfN(_, genEvent))

  implicit val arbitraryEvents: Arbitrary[List[EventPayload]] = Arbitrary(genEvents)

  def genEvent(indexedEvent: IndexedEventType, values: Map[EventField.Value, String] = Map.empty): Gen[Event] = for {
    device <- Gen.uuid.map(DeviceId.apply)
    eventId <- Gen.uuid.map(_.toString)
    eventType = EventType(indexedEvent.toString, 0)
    json      = Json.obj(values.map { case (k, v) => k.toString -> v.asJson }.toSeq: _*)
  } yield Event(device, eventId, eventType, Instant.now, Instant.now, json)

  private def genEventProps(indexedEvent: IndexedEventType): Map[EventField.Value, Json] = indexedEvent match {
    case DownloadComplete =>
      Map.empty
    case InstallationComplete =>
      genEventProps(DownloadComplete) + (CORRELATION_ID -> genCorrelationId.generate.asJson)
    case InstallationReport =>
      genEventProps(InstallationComplete) + (RESULT_CODE -> genResultCode.generate.toString.asJson)
    case EcuInstallationReport =>
      genEventProps(InstallationReport) + (ECU_SERIAL -> genEcuSerial.generate.asJson)
  }

  def genEventPayload(indexedEvent: IndexedEventType): Gen[(EventPayload, Map[EventField.Value, String])] = for {
    id        <- Gen.uuid
    timestamp <- genInstant
    eventProps = genEventProps(indexedEvent)
    json       = Json.obj(eventProps.toSeq.map { case (k, v) => k.toString -> v }: _*)
    eventType = EventType(indexedEvent.toString, 0)
  } yield EventPayload(id, timestamp, eventType, json.asJson) -> eventProps.mapValues(_.asString.get)

}

object EventGenerators extends EventGenerators {

  final case class EventPayload(id: UUID, deviceTime: Instant, eventType: EventType, event: Json)

  implicit val eventPayloadEncoder: ObjectEncoder[EventPayload] = deriveEncoder[EventPayload]

  implicit val eventPayloadFromResponse: Decoder[EventPayload] =
    Decoder.instance { c =>
      for {
        id         <- c.get[String]("eventId").map(UUID.fromString)
        deviceTime <- c.get[Instant]("deviceTime")
        eventType  <- c.get[EventType]("eventType")
        payload    <- c.get[Json]("payload")
      } yield EventPayload(id, deviceTime, eventType, payload)
    }
}