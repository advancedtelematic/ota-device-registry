/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.ota.deviceregistry.data.{GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.data.Group.Name
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType

import scala.concurrent.ExecutionContext

trait GroupRequests {
  self: ResourceSpec =>

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  val groupsApi = "device_groups"

  def listDevicesInGroup(groupId: Uuid, offset: Option[Long] = None, limit: Option[Long] = None)(
      implicit ec: ExecutionContext
  ): HttpRequest =
    (offset, limit) match {
      case (None, None) =>
        Get(Resource.uri("device_groups", groupId.underlying.value, "devices"))
      case _ =>
        Get(
          Resource
            .uri("device_groups", groupId.underlying.value, "devices")
            .withQuery(
              Query("offset" -> offset.getOrElse(0).toString, "limit" -> limit.getOrElse(50).toString)
            )
        )
    }

  def getGroupDetails(groupId: Uuid)(implicit ec: ExecutionContext): HttpRequest =
    Get(Resource.uri("device_groups", groupId.underlying.value))

  def countDevicesInGroup(groupId: Uuid)(implicit ec: ExecutionContext): HttpRequest =
    Get(Resource.uri("device_groups", groupId.underlying.value, "count"))

  def listGroups(): HttpRequest = Get(Resource.uri(groupsApi))

  def createGroup(groupName: Name, groupType: GroupType = GroupType.static, expression: String)(
      implicit ec: ExecutionContext
  ): HttpRequest =
    Post(
      Resource
        .uri(groupsApi)
        .withQuery(Query("groupName" -> groupName.value, "type" -> groupType.toString, "expression" -> expression))
    )

  def createGroupOk(groupName: Name)(implicit ec: ExecutionContext): Uuid =
    createGroup(groupName, GroupType.static, "") ~> route ~> check {
      status shouldBe Created
      responseAs[Uuid]
    }

  def createDynamicGroupOk(groupName: Name, expression: String)(implicit ec: ExecutionContext): Uuid =
    createGroup(groupName, GroupType.dynamic, expression) ~> route ~> check {
      status shouldBe Created
      responseAs[Uuid]
    }

  def addDeviceToGroup(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri(groupsApi, groupId.underlying.value, "devices", deviceId.underlying.value))

  def addDeviceToGroupOk(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): Unit =
    addDeviceToGroup(groupId, deviceId) ~> route ~> check {
      status shouldBe OK
    }

  def removeDeviceFromGroup(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): HttpRequest =
    Delete(Resource.uri(groupsApi, groupId.underlying.value, "devices", deviceId.underlying.value))

  def renameGroup(id: Uuid, newGroupName: Name)(implicit ec: ExecutionContext): HttpRequest =
    Put(
      Resource
        .uri(groupsApi, id.underlying.value, "rename")
        .withQuery(Query("groupName" -> newGroupName.value))
    )
}
