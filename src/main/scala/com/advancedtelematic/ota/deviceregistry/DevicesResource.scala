/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import java.time.{Instant, OffsetDateTime}

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import akka.stream.ActorMaterializer
import cats.syntax.either._
import cats.syntax.show._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.{CorrelationId, DeviceOemId, Namespace}
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event, EventType}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceEventMessage
import com.advancedtelematic.ota.deviceregistry.DevicesResource.EventPayload
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.InstallationStatsLevel.InstallationStatsLevel
import com.advancedtelematic.ota.deviceregistry.data.DataType.{DeviceT, InstallationStatsLevel, SearchParams, UpdateDevice}
import com.advancedtelematic.ota.deviceregistry.data.Device.ActiveDeviceCount
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{CsvSerializer, PackageId}
import com.advancedtelematic.ota.deviceregistry.db._
import com.advancedtelematic.ota.deviceregistry.messages.{DeleteDeviceRequest, DeviceCreated}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.Json
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

object DevicesResource {

  type EventPayload = (DeviceId, Instant) => Event

  private[DevicesResource] implicit val EventPayloadDecoder: io.circe.Decoder[EventPayload] =
    io.circe.Decoder.instance { c =>
      for {
        id         <- c.get[String]("id")
        deviceTime <- c.get[Instant]("deviceTime")(io.circe.java8.time.decodeInstant)
        eventType  <- c.get[EventType]("eventType")
        payload    <- c.get[Json]("event")
      } yield
        (deviceUuid: DeviceId, receivedAt: Instant) =>
          Event(deviceUuid, id, eventType, deviceTime, receivedAt, payload)
    }
}

class DevicesResource(
    namespaceExtractor: Directive1[AuthedNamespaceScope],
    messageBus: MessageBusPublisher,
    deviceNamespaceAuthorizer: Directive1[DeviceId]
)(implicit system: ActorSystem, db: Database, mat: ActorMaterializer, ec: ExecutionContext) {

  import Directives._
  import StatusCodes._
  import com.advancedtelematic.libats.http.AnyvalMarshallingSupport._
  import com.advancedtelematic.libats.http.RefinedMarshallingSupport._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  val extractPackageId: Directive1[PackageId] =
    pathPrefix(Segment / Segment).as(PackageId.apply)

  val eventJournal = new EventJournal()

  implicit val groupIdUnmarshaller: Unmarshaller[String, GroupId] = GroupId.unmarshaller

  implicit val correlationIdUnmarshaller: FromStringUnmarshaller[CorrelationId] = Unmarshaller.strict {
    CorrelationId.fromString(_).leftMap(new IllegalArgumentException(_)).valueOr(throw _)
  }

  implicit val installationStatsLevelUnmarshaller: FromStringUnmarshaller[InstallationStatsLevel] =
    Unmarshaller.strict {
      _.toLowerCase match {
        case "device" => InstallationStatsLevel.Device
        case "ecu"    => InstallationStatsLevel.Ecu
        case s        => throw new IllegalArgumentException(s"Invalid value for installation stats level parameter: $s.")
      }
  }

  implicit val installationFailureCsvMarshaller: ToResponseMarshaller[Seq[(DeviceOemId, String, String)]] =
    Marshaller.withFixedContentType(ContentTypes.`text/csv(UTF-8)`) { t =>
      val csv = CsvSerializer.asCsv(Seq("Device ID", "Failure Code", "Failure Description"), t)
      val e = HttpEntity(ContentTypes.`text/csv(UTF-8)`, csv)
      val h = `Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> "device-failures.csv"))
      HttpResponse(headers = h :: Nil, entity = e)
    }

  def searchDevice(ns: Namespace): Route =
    parameters((
      'deviceId.as[DeviceOemId].?,
      'grouped.as[Boolean].?,
      'groupType.as[GroupType].?,
      'groupId.as[GroupId].?,
      'regex.as[String Refined Regex].?,
      'offset.as[Long].?,
      'limit.as[Long].?)).as(SearchParams)
    { params => complete(db.run(DeviceRepository.search(ns, params))) }

  def createDevice(ns: Namespace, device: DeviceT): Route = {
    val f = db
      .run(DeviceRepository.create(ns, device))
      .andThen {
        case scala.util.Success(uuid) =>
          messageBus.publish(
            DeviceCreated(ns, uuid, device.deviceName, device.deviceId, device.deviceType, Instant.now())
          )
      }

    onSuccess(f) { uuid =>
      respondWithHeaders(List(Location(Uri("/devices/" + uuid.show)))) {
        complete(Created -> uuid)
      }
    }
  }

  def deleteDevice(ns: Namespace, uuid: DeviceId): Route = {
    val f = messageBus.publish(DeleteDeviceRequest(ns, uuid, Instant.now()))
    onSuccess(f) { complete(StatusCodes.Accepted) }
  }

  def fetchDevice(uuid: DeviceId): Route =
    complete(db.run(DeviceRepository.findByUuid(uuid)))

  def updateDevice(ns: Namespace, uuid: DeviceId, updateDevice: UpdateDevice): Route =
    complete(db.run(DeviceRepository.updateDeviceName(ns, uuid, updateDevice.deviceName)))

  def countDynamicGroupCandidates(ns: Namespace, expression: GroupExpression): Route =
    complete(db.run(DeviceRepository.countDevicesForExpression(ns, expression)))

  def getGroupsForDevice(ns: Namespace, uuid: DeviceId): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      complete(db.run(GroupMemberRepository.listGroupsForDevice(ns, uuid, offset, limit)))
    }

  def updateInstalledSoftware(device: DeviceId): Route =
    entity(as[Seq[PackageId]]) { installedSoftware =>
      val f = db.run(InstalledPackages.setInstalled(device, installedSoftware.toSet))
      onSuccess(f) { complete(StatusCodes.NoContent) }
    }

  def getDevicesCount(pkg: PackageId, ns: Namespace): Route =
    complete(db.run(InstalledPackages.getDevicesCount(pkg, ns)))

  def listPackagesOnDevice(device: DeviceId): Route =
    parameters(('regex.as[String Refined Regex].?, 'offset.as[Long].?, 'limit.as[Long].?)) { (regex, offset, limit) =>
      complete(db.run(InstalledPackages.installedOn(device, regex, offset, limit)))
    }

  implicit def offsetDateTimeUnmarshaller: FromStringUnmarshaller[OffsetDateTime] =
    Unmarshaller.strict(OffsetDateTime.parse)

  def getActiveDeviceCount(ns: Namespace): Route =
    parameters(('start.as[OffsetDateTime], 'end.as[OffsetDateTime])) { (start, end) =>
      complete(
        db.run(DeviceRepository.countActivatedDevices(ns, start.toInstant, end.toInstant))
          .map(ActiveDeviceCount.apply)
      )
    }

  def getDistinctPackages(ns: Namespace): Route =
    parameters('offset.as[Long].?, 'limit.as[Long].?) { (offset, limit) =>
      complete(db.run(InstalledPackages.getInstalledForAllDevices(ns, offset, limit)))
    }

  def findAffected(ns: Namespace): Route =
    entity(as[Set[PackageId]]) { packageIds =>
      val f = InstalledPackages.allInstalledPackagesById(ns, packageIds).map {
        _.groupBy(_._1).mapValues(_.map(_._2).toSet)
      }
      complete(db.run(f))
    }

  def getPackageStats(ns: Namespace, name: PackageId.Name): Route =
    parameters('offset.as[Long].?, 'limit.as[Long].?) { (offset, limit) =>
      val f = db.run(InstalledPackages.listAllWithPackageByName(ns, name, offset, limit))
      complete(f)
    }

  def fetchInstallationHistory(deviceId: DeviceId, offset: Option[Long], limit: Option[Long]): Route =
    complete(db.run(InstallationReportRepository.fetchInstallationHistory(deviceId, offset, limit)))

  def fetchInstallationStats(correlationId: CorrelationId, reportLevel: Option[InstallationStatsLevel]): Route = {
    val action = reportLevel match {
      case Some(InstallationStatsLevel.Ecu) => InstallationReportRepository.installationStatsPerEcu(correlationId)
      case _                                => InstallationReportRepository.installationStatsPerDevice(correlationId)
    }
    complete(db.run(action))
  }

  def fetchFailureStats(correlationId: CorrelationId, failureCode: Option[String]): Route = {
    val f = db.run(InstallationReportRepository.fetchDeviceFailures(correlationId, failureCode))
    onSuccess(f) { s =>
      respondWithHeader(`Content-Type`(ContentTypes.`text/csv(UTF-8)`)) {
        complete(s)
      }
    }
  }

  def api: Route = namespaceExtractor { ns =>
    val scope = Scopes.devices(ns)
    pathPrefix("devices") {
      (scope.post & entity(as[DeviceT]) & pathEnd) { device =>
        createDevice(ns.namespace, device)
      } ~
      scope.get {
        (path("count") & parameter('expression.as[GroupExpression].?)) {
          case None      => complete(Errors.InvalidGroupExpression(""))
          case Some(exp) => countDynamicGroupCandidates(ns.namespace, exp)
        } ~
        (path("failed-installations.csv") & parameters('correlationId.as[CorrelationId], 'failureCode.as[String].?)) {
          (cid, fc) => fetchFailureStats(cid, fc)
        } ~
        (path("stats") & parameters('correlationId.as[CorrelationId], 'reportLevel.as[InstallationStatsLevel].?)) {
          (cid, reportLevel) => fetchInstallationStats(cid, reportLevel)
        } ~
        pathEnd {
          searchDevice(ns.namespace)
        }
      } ~
      deviceNamespaceAuthorizer { uuid =>
        scope.get {
          path("groups") {
            getGroupsForDevice(ns.namespace, uuid)
          } ~
          path("packages") {
            listPackagesOnDevice(uuid)
          } ~
          path("active_device_count") {
            getActiveDeviceCount(ns.namespace)
          } ~
          (path("installation_history") & parameters('offset.as[Long].?, 'limit.as[Long].?)) {
            (offset, limit) => fetchInstallationHistory(uuid, offset, limit)
          } ~
          (pathPrefix("device_count") & extractPackageId) { pkg =>
            getDevicesCount(pkg, ns.namespace)
          } ~
          pathEnd {
            fetchDevice(uuid)
          }
        } ~
        (scope.put & entity(as[UpdateDevice]) & pathEnd) { updateBody =>
          updateDevice(ns.namespace, uuid, updateBody)
        } ~
        (scope.delete & pathEnd) {
          deleteDevice(ns.namespace, uuid)
        } ~
        path("events") {
          import DevicesResource.EventPayloadDecoder
          (get & parameter('correlationId.as[CorrelationId].?)) { correlationId =>
            val events = eventJournal.getEvents(uuid, correlationId)
            complete(events)
          } ~
          (post & pathEnd) {
            extractLog { log =>
              entity(as[List[EventPayload]]) { xs =>
                val timestamp = Instant.now()
                val recordingResult: List[Future[Unit]] =
                  xs.map(_.apply(uuid, timestamp)).map(x => messageBus.publish(DeviceEventMessage(ns.namespace, x)))
                onComplete(Future.sequence(recordingResult)) {
                  case scala.util.Success(_) =>
                    complete(StatusCodes.NoContent)

                  case scala.util.Failure(t) =>
                    log.error(t, "Unable write events to log.")
                    complete(StatusCodes.ServiceUnavailable)
                }
              }
            }
          }
        }
      }
    } ~
    (scope.get & pathPrefix("device_count") & extractPackageId) { pkg =>
      getDevicesCount(pkg, ns.namespace)
    } ~
    (scope.get & path("active_device_count")) {
      getActiveDeviceCount(ns.namespace)
    }
  }

  def mydeviceRoutes: Route = namespaceExtractor { authedNs => // Don't use this as a namespace
    pathPrefix("mydevice" / DeviceId.Path) { uuid =>
      (get & pathEnd & authedNs.oauthScopeReadonly(s"ota-core.${uuid.show}.read")) {
        fetchDevice(uuid)
      } ~
      (put & path("packages") & authedNs.oauthScope(s"ota-core.${uuid.show}.write")) {
        updateInstalledSoftware(uuid)
      }
    }
  }

  val devicePackagesRoutes: Route = namespaceExtractor { authedNs =>
    val scope = Scopes.devices(authedNs)
    pathPrefix("device_packages") {
      (pathEnd & scope.get) {
        getDistinctPackages(authedNs.namespace)
      } ~
      (path(Segment) & scope.get) { name =>
        getPackageStats(authedNs.namespace, name)
      } ~
      (path("affected") & scope.post) {
        findAffected(authedNs.namespace)
      }
    }
  }

  val route: Route = api ~ mydeviceRoutes ~ devicePackagesRoutes
}
