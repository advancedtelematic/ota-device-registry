/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import cats.syntax.show._
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId._
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{GroupType, Uuid}
import io.circe.Json

import scala.concurrent.ExecutionContext

trait GroupRequests {
  self: ResourceSpec =>

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  val groupsApi = "device_groups"

  def listDevicesInGroup(groupId: GroupId, offset: Option[Long] = None, limit: Option[Long] = None)(
      implicit ec: ExecutionContext
  ): HttpRequest =
    (offset, limit) match {
      case (None, None) =>
        Get(Resource.uri("device_groups", groupId.show, "devices"))
      case _ =>
        Get(
          Resource
            .uri("device_groups", groupId.show, "devices")
            .withQuery(
              Query("offset" -> offset.getOrElse(0).toString, "limit" -> limit.getOrElse(50).toString)
            )
        )
    }

  def getGroupDetails(groupId: GroupId)(implicit ec: ExecutionContext): HttpRequest =
    Get(Resource.uri("device_groups", groupId.show))

  def countDevicesInGroup(groupId: GroupId)(implicit ec: ExecutionContext): HttpRequest =
    Get(Resource.uri("device_groups", groupId.show, "count"))

  def listGroups(): HttpRequest = Get(Resource.uri(groupsApi))

  def createGroup(groupName: Name, groupType: GroupType = GroupType.static, expression: Option[GroupExpression] = None)(
      implicit ec: ExecutionContext
  ): HttpRequest = {
    val req = CreateGroup(groupName, groupType, expression)
    Post(Resource.uri(groupsApi), req)
  }

  def createGroupOk(groupName: Name)(implicit ec: ExecutionContext): GroupId =
    createGroup(groupName, GroupType.static) ~> route ~> check {
      status shouldBe Created
      responseAs[GroupId]
    }

  def createDynamicGroupOk(groupName: Name, expression: GroupExpression)(implicit ec: ExecutionContext): GroupId =
    createGroup(groupName, GroupType.dynamic, Some(expression)) ~> route ~> check {
      status shouldBe Created
      responseAs[GroupId]
    }

  def addDeviceToGroup(groupId: GroupId, deviceUuid: Uuid)(implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri(groupsApi, groupId.show, "devices", deviceUuid.show))

  def addDeviceToGroupOk(groupId: GroupId, deviceUuid: Uuid)(implicit ec: ExecutionContext): Unit =
    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe OK
    }

  def removeDeviceFromGroup(groupId: GroupId, deviceId: Uuid)(implicit ec: ExecutionContext): HttpRequest =
    Delete(Resource.uri(groupsApi, groupId.show, "devices", deviceId.underlying.value))

  def renameGroup(groupId: GroupId, newGroupName: Name)(implicit ec: ExecutionContext): HttpRequest =
    Put(
      Resource
        .uri(groupsApi, groupId.show, "rename")
        .withQuery(Query("groupName" -> newGroupName.value))
    )
}
