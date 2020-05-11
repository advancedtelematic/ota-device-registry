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
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import akka.stream.ActorMaterializer
import cats.syntax.either._
import cats.syntax.show._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace, ResultCode}
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.http.ValidatedGenericMarshalling.validatedStringUnmarshaller
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId._
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event, EventType}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceEventMessage
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.InstallationStatsLevel.InstallationStatsLevel
import com.advancedtelematic.ota.deviceregistry.data.DataType.{WriteDeviceTag, DeviceT, InstallationStatsLevel, SearchParams, UpdateDevice}
import com.advancedtelematic.ota.deviceregistry.data.Device.{ActiveDeviceCount, DeviceOemId}
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.data.{GroupExpression, PackageId, SortBy}
import com.advancedtelematic.ota.deviceregistry.db._
import com.advancedtelematic.ota.deviceregistry.messages.{DeleteDeviceRequest, DeviceCreated}
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

  implicit val groupIdUnmarshaller: Unmarshaller[String, GroupId] = GroupId.unmarshaller

  implicit val resultCodeUnmarshaller: FromStringUnmarshaller[ResultCode] = Unmarshaller.strict(ResultCode)

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

  implicit val sortByUnmarshaller: FromStringUnmarshaller[SortBy] = Unmarshaller.strict {
    _.toLowerCase match {
      case "name"      => SortBy.Name
      case "createdat" => SortBy.CreatedAt
      case s           => throw new IllegalArgumentException(s"Invalid value for sorting parameter: '$s'.")
    }
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
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  import DevicesResource._

  val extractPackageId: Directive1[PackageId] =
    pathPrefix(Segment / Segment).as(PackageId.apply)

  val eventJournal = new EventJournal()

  def searchDevice(ns: Namespace): Route =
    parameters((
      'deviceId.as[DeviceOemId].?,
      'grouped.as[Boolean].?,
      'groupType.as[GroupType].?,
      'groupId.as[GroupId].?,
      'nameContains.as[String].?,
      'notSeenSinceHours.as[Int].?,
      'sortBy.as[SortBy].?,
      'offset.as[Long].?,
      'limit.as[Long].?)).as(SearchParams.apply _)
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
    parameters(('nameContains.as[String].?, 'offset.as[Long].?, 'limit.as[Long].?)) { (nameContains, offset, limit) =>
      complete(db.run(InstalledPackages.installedOn(device, nameContains, offset, limit)))
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

  private def fetchDeviceTags(ns: Namespace): Route =
    complete(db.run(DeviceTagRepository.fetchAll(ns)))

  private def createDeviceTag(ns: Namespace, tagName: String): Route =
    complete(db.run(DeviceTagRepository.create(ns, tagName)))

  private def renameDeviceTag(ns: Namespace, tagId: Int, tagName: String): Route =
    complete(db.run(DeviceTagRepository.rename(ns, tagId, tagName).map(_ => ())))

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
        (scope.put & pathEnd & entity(as[UpdateDevice])) { updateBody =>
          updateDevice(ns.namespace, uuid, updateBody)
        } ~
        (scope.delete & pathEnd) {
          deleteDevice(ns.namespace, uuid)
        } ~
        path("events") {
          import DevicesResource.EventPayloadDecoder
          (get & parameter('correlationId.as[CorrelationId].?)) { correlationId =>
            // TODO: This should not return raw Events
            // https://saeljira.it.here.com/browse/OTA-4163
            // API should not return arbitrary json (`payload`) to the clients. This is why we index interesting events, so we can give this info to clients
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
    pathPrefix("device_tags") {
      (pathEnd & scope.get) {
        fetchDeviceTags(ns.namespace)
      } ~
      (pathEnd & scope.post & entity(as[WriteDeviceTag])) { body =>
        createDeviceTag(ns.namespace, body.name)
      } ~
      (path(IntNumber) & scope.patch & entity(as[WriteDeviceTag])) { (tagId, body) =>
        renameDeviceTag(ns.namespace, tagId, body.name)
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
