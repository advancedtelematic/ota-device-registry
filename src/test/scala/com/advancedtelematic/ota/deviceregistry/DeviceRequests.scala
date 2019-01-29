/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import java.time.OffsetDateTime

import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.syntax.show._
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace}
import com.advancedtelematic.libats.http.HttpOps.HttpRequestOps
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.InstallationStatsLevel.InstallationStatsLevel
import com.advancedtelematic.ota.deviceregistry.data.DataType.{DeviceT, UpdateDevice}
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{DeviceName, GroupExpression, PackageId}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json

import scala.concurrent.ExecutionContext

/**
  * Generic test resource object
  * Used in property-based testing
  */
object Resource {
  def uri(pathSuffixes: String*): Uri = {
    val BasePath = Path("/api") / "v1"
    Uri.Empty.withPath(pathSuffixes.foldLeft(BasePath)(_ / _))
  }
}

/**
  * Testing Trait for building Device requests
  */
trait DeviceRequests { self: ResourceSpec =>

  import StatusCodes._
  import com.advancedtelematic.ota.deviceregistry.data.Device._

  val api = "devices"

  def fetchDevice(uuid: DeviceId): HttpRequest =
    Get(Resource.uri(api, uuid.show))

  def listDevices(): HttpRequest =
    Get(Resource.uri(api))

  def searchDevice(regex: String, offset: Long = 0, limit: Long = 50): HttpRequest =
    Get(
      Resource
        .uri(api)
        .withQuery(Query("regex" -> regex, "offset" -> offset.toString, "limit" -> limit.toString))
    )

  def fetchByDeviceId(deviceId: DeviceOemId, nameContains: Option[String] = None, groupId: Option[GroupId] = None): HttpRequest = {
    val m = nameContains.map("nameContains" -> _).toMap ++ groupId.map("groupId" -> _.show).toMap + ("deviceId" -> deviceId.show)
    Get(Resource.uri(api).withQuery(Query(m)))
  }

  def fetchByGroupId(groupId: GroupId, offset: Long = 0, limit: Long = 50): HttpRequest =
    Get(
      Resource
        .uri(api)
        .withQuery(
          Query("groupId" -> groupId.show, "offset" -> offset.toString, "limit" -> limit.toString)
        )
    )

  def fetchUngrouped(offset: Long = 0, limit: Long = 50): HttpRequest =
    Get(
      Resource
        .uri(api)
        .withQuery(
          Query("grouped" -> "false", "offset" -> offset.toString, "limit" -> limit.toString)
        )
    )

  def updateDevice(uuid: DeviceId, newName: DeviceName)(implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri(api, uuid.show), UpdateDevice(newName))

  def createDevice(device: DeviceT)(implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri(api), device)

  def createDeviceOk(device: DeviceT)(implicit ec: ExecutionContext): DeviceId =
    createDevice(device) ~> route ~> check {
      status shouldBe Created
      responseAs[DeviceId]
  }

  def createDeviceInNamespaceOk(device: DeviceT, ns: Namespace)(implicit ec: ExecutionContext): DeviceId =
    Post(Resource.uri(api), device).withNs(ns) ~> route ~> check {
      status shouldBe Created
      responseAs[DeviceId]
    }

  def deleteDevice(uuid: DeviceId)(implicit ec: ExecutionContext): HttpRequest =
    Delete(Resource.uri(api, uuid.show))

  def fetchSystemInfo(uuid: DeviceId): HttpRequest =
    Get(Resource.uri(api, uuid.show, "system_info"))

  def createSystemInfo(uuid: DeviceId, json: Json)(implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri(api, uuid.show, "system_info"), json)

  def updateSystemInfo(uuid: DeviceId, json: Json)(implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri(api, uuid.show, "system_info"), json)

  def fetchNetworkInfo(uuid: DeviceId)(implicit ec: ExecutionContext): HttpRequest = {
    val uri = Resource.uri(api, uuid.show, "system_info", "network")
    Get(uri)
  }

  def listGroupsForDevice(device: DeviceId)(implicit ec: ExecutionContext): HttpRequest =
    Get(Resource.uri(api, device.show, "groups"))

  def installSoftware(device: DeviceId, packages: Set[PackageId]): HttpRequest =
    Put(Resource.uri("mydevice", device.show, "packages"), packages)

  def installSoftwareOk(device: DeviceId, packages: Set[PackageId])(implicit route: Route): Unit =
    installSoftware(device, packages) ~> route ~> check {
      status shouldBe StatusCodes.NoContent
    }

  def listPackages(device: DeviceId, regex: Option[String] = None)(implicit ec: ExecutionContext): HttpRequest =
    regex match {
      case Some(r) =>
        Get(Resource.uri("devices", device.show, "packages").withQuery(Query("regex" -> r)))
      case None => Get(Resource.uri("devices", device.show, "packages"))
    }

  def getStatsForPackage(pkg: PackageId)(implicit ec: ExecutionContext): HttpRequest =
    Get(Resource.uri("device_count", pkg.name, pkg.version))

  def getActiveDeviceCount(start: OffsetDateTime, end: OffsetDateTime): HttpRequest =
    Get(
      Resource.uri("active_device_count").withQuery(Query("start" -> start.show, "end" -> end.show))
    )

  def getInstalledForAllDevices(offset: Long = 0, limit: Long = 50): HttpRequest =
    Get(
      Resource
        .uri("device_packages")
        .withQuery(Query("offset" -> offset.toString, "limit" -> limit.toString))
    )

  def getAffected(pkgs: Set[PackageId]): HttpRequest =
    Post(Resource.uri("device_packages", "affected"), pkgs)

  def getPackageStats(name: PackageId.Name): HttpRequest =
    Get(Resource.uri("device_packages", name))

  def countDevicesForExpression(expression: Option[GroupExpression]): HttpRequest =
    Get(Resource.uri(api, "count").withQuery(Query(expression.map("expression" -> _.value).toMap)))

  def getEvents(deviceUuid: DeviceId, correlationId: Option[CorrelationId] = None): HttpRequest = {
    val query = Query(correlationId.map("correlationId" -> _.toString).toMap)
    Get(Resource.uri(api, deviceUuid.show, "events").withQuery(query))
  }

  def getGroupsOfDevice(deviceUuid: DeviceId): HttpRequest = Get(Resource.uri(api, deviceUuid.show, "groups"))

  def getDevicesByGrouping(grouped: Boolean, groupType: Option[GroupType],
                           nameContains: Option[String] = None, limit: Long = 1000): HttpRequest = {
    val m = Map("grouped" -> grouped, "limit" -> limit) ++
      List("groupType" -> groupType, "nameContains" -> nameContains).collect { case (k, Some(v)) => k -> v }.toMap
    Get(Resource.uri(api).withQuery(Query(m.mapValues(_.toString))))
  }

  def getStats(correlationId: CorrelationId, level: InstallationStatsLevel): HttpRequest =
    Get(Resource.uri(api, "stats").withQuery(Query("correlationId" -> correlationId.toString, "level" -> level.toString)))

  def getFailedExport(correlationId: CorrelationId, failureCode: Option[String]): HttpRequest = {
    val m = Map("correlationId" -> correlationId.toString)
    val params = failureCode.fold(m)(fc => m + ("failureCode" -> fc))
    Get(Resource.uri(api, "failed-installations.csv").withQuery(Query(params)))
  }

  def getReportBlob(deviceId: DeviceId): HttpRequest =
    Get(Resource.uri(api, deviceId.show, "installation_history"))
}
