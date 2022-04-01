package com.advancedtelematic.ota.deviceregistry

import akka.actor.Scheduler

import java.time.Instant
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.DevicesResource.correlationIdUnmarshaller
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import com.advancedtelematic.ota.deviceregistry.db.EventJournal
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType
import com.advancedtelematic.ota.deviceregistry.DeviceResource2.{ApiDeviceEvent, ApiDeviceEvents}
import com.advancedtelematic.ota.deviceregistry.http.nonNegativeLong

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

object DeviceResource2 {
  import io.circe.generic.semiauto._

  type ApiUpdateId = CorrelationId

  type ApiDeviceUpdateEventName = IndexedEventType

  case class ApiDeviceEvent(ecuId: Option[EcuIdentifier], updateId: Option[ApiUpdateId], name: ApiDeviceUpdateEventName,
                            receivedTime: Instant, deviceTime: Instant)

  case class ApiDeviceEvents(deviceUuid: DeviceId, events: Vector[ApiDeviceEvent], total: Long, offset: Long, limit: Long)


  implicit val apiDeviceUpdateEventNameCodec = io.circe.Codec.codecForEnumeration(IndexedEventType)

  implicit val apiDeviceUpdateCodec = deriveCodec[ApiDeviceEvent]

  implicit val apiUpdateStatusCodec = deriveCodec[ApiDeviceEvents]
}

class DeviceResource2(namespaceExtractor: Directive1[AuthedNamespaceScope], deviceNamespaceAuthorizer: Directive1[DeviceId])
                     (implicit db: Database, ec: ExecutionContext, scheduler: Scheduler) extends Settings {

  val eventJournal = new EventJournal()

  def findUpdateEvents(namespace: Namespace, deviceId: DeviceId, correlationId: Option[CorrelationId], offset: Long, limit: Long): Future[ApiDeviceEvents] = async {
    val indexedEvents = await(eventJournal.getPaginatedIndexedEvents(deviceId, correlationId, offset, limit))

    val events = indexedEvents.values.toVector.map { case (event, indexedEvent) =>
      val ecuO = event.payload.hcursor.downField("ecu").as[EcuIdentifier].toOption
      ApiDeviceEvent(ecuO, indexedEvent.correlationId, indexedEvent.eventType, event.receivedAt, event.deviceTime)
    }

    ApiDeviceEvents(deviceId, events, indexedEvents.total, indexedEvents.offset, indexedEvents.limit)
  }

  def route: Route = namespaceExtractor { ns =>
    pathPrefix("devices") {
      deviceNamespaceAuthorizer { uuid =>
        (get & path("events") & parameters('updateId.as[CorrelationId].?, 'offset.as(nonNegativeLong).?(0), 'limit.as(nonNegativeLong).?(maxAllowedDeviceEventsLimit))) {
          (correlationId, offset, limit) =>
            val f = findUpdateEvents(ns.namespace, uuid, correlationId, offset, limit.min(maxAllowedDeviceEventsLimit))
            complete(f)
        }
      }
    }
  }
}
