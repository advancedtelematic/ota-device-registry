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
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import akka.stream.ActorMaterializer
import cats.syntax.show._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.ota.deviceregistry.data.{DeviceT, PackageId, Uuid}
import com.advancedtelematic.ota.deviceregistry.data.Device.{ActiveDeviceCount, DeviceId}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupMemberRepository, InstalledPackages}
import com.advancedtelematic.ota.deviceregistry.messages.DeviceCreated
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import io.circe.KeyEncoder
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class DevicesResource(
    namespaceExtractor: Directive1[AuthedNamespaceScope],
    messageBus: MessageBusPublisher,
    deviceNamespaceAuthorizer: Directive1[Uuid]
)(implicit system: ActorSystem, db: Database, mat: ActorMaterializer, ec: ExecutionContext) {

  import StatusCodes._
  import Directives._
  import com.advancedtelematic.libats.http.RefinedMarshallingSupport._
  import UuidDirectives._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  val extractPackageId: Directive1[PackageId] =
    pathPrefix(Segment / Segment).as(PackageId.apply)

  def searchDevice(ns: Namespace): Route =
    parameters(
      ('regex.as[String Refined Regex].?,
       'deviceId.as[String].?, // TODO: Use refined
       'groupId.as[String Refined Uuid.Valid].?,
       'ungrouped ? false,
       'offset.as[Long].?,
       'limit.as[Long].?)
    ) {
      case (re, None, groupId, false, offset, limit) =>
        complete(db.run(DeviceRepository.search(ns, re, groupId.map(Uuid(_)), offset, limit)))
      case (re, None, None, true, offset, limit) =>
        complete(db.run(DeviceRepository.searchUngrouped(ns, re, offset, limit)))
      case (None, Some(deviceId), None, false, _, _) =>
        complete(db.run(DeviceRepository.findByDeviceId(ns, DeviceId(deviceId))))
      case _ =>
        complete((BadRequest, "'regex' and 'deviceId' parameters cannot be used together!"))
    }

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

  def fetchDevice(uuid: Uuid): Route =
    complete(db.run(DeviceRepository.findByUuid(uuid)))

  def updateDevice(ns: Namespace, uuid: Uuid, device: DeviceT): Route =
    complete(db.run(DeviceRepository.update(ns, uuid, device)))

  def getGroupsForDevice(uuid: Uuid): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      complete(db.run(GroupMemberRepository.listGroupsForDevice(uuid, offset, limit)))
    }

  def updateInstalledSoftware(device: Uuid): Route =
    entity(as[Seq[PackageId]]) { installedSoftware =>
      val f = db.run(InstalledPackages.setInstalled(device, installedSoftware.toSet))
      onSuccess(f) { complete(StatusCodes.NoContent) }
    }

  def getDevicesCount(pkg: PackageId, ns: Namespace): Route =
    complete(db.run(InstalledPackages.getDevicesCount(pkg, ns)))

  def listPackagesOnDevice(device: Uuid): Route =
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
      implicit val UuidKeyEncoder: KeyEncoder[Uuid] = (uuid: Uuid) => uuid.underlying.value
      complete(db.run(f))
    }

  def getPackageStats(ns: Namespace, name: PackageId.Name): Route =
    parameters('offset.as[Long].?, 'limit.as[Long].?) { (offset, limit) =>
      val f = db.run(InstalledPackages.listAllWithPackageByName(ns, name, offset, limit))
      complete(f)
    }

  def api: Route = namespaceExtractor { ns =>
    val scope = Scopes.devices(ns)
    pathPrefix("devices") {
      (scope.post & entity(as[DeviceT]) & pathEndOrSingleSlash) { device =>
        createDevice(ns.namespace, device)
      } ~
      (scope.get & pathEnd) { searchDevice(ns.namespace) } ~
      deviceNamespaceAuthorizer { uuid =>
        (scope.put & entity(as[DeviceT]) & pathEnd) { device =>
          updateDevice(ns.namespace, uuid, device)
        } ~
        (scope.get & pathEnd) {
          fetchDevice(uuid)
        } ~
        (scope.get & path("groups") & pathEnd) {
          getGroupsForDevice(uuid)
        } ~
        (path("packages") & scope.get) {
          listPackagesOnDevice(uuid)
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
    (pathPrefix("mydevice") & extractUuid) { uuid =>
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

  /**
    * Base API route for devices.
    *
    * @return      Route object containing routes for creating, deleting, and listing devices
    */
  def route: Route = api ~ mydeviceRoutes ~ devicePackagesRoutes

}
