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
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId._
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name, ValidExpression}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.ota.deviceregistry.data.GroupType
import eu.timepit.refined.api.Refined

import scala.concurrent.ExecutionContext
import scala.util.Random

trait GroupRequests {
  self: ResourceSpec =>

  private val defaultExpression = Refined.unsafeApply[String, ValidExpression]("deviceid contains abcd")
  protected val groupsApi = "device_groups"

  def listDevicesInGroup(groupId: GroupId, offset: Option[Long] = None, limit: Option[Long] = None)
                        (implicit ec: ExecutionContext): HttpRequest =
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

  def listGroups(sortBy: Option[SortBy] = None, limit : Option[Long] = None, nameContains: Option[String] = None): HttpRequest = {
    val m = List("sortBy" -> sortBy, "limit" -> limit, "nameContains" -> nameContains).collect { case (k, Some(v)) => k -> v.toString }.toMap
    Get(Resource.uri(groupsApi).withQuery(Query(m)))
  }

  // TODO easier if expression comes in as a String
  def createGroup(groupType: GroupType, expression: Option[GroupExpression], groupName: Option[Name] = None)
                 (implicit ec: ExecutionContext): HttpRequest = {
    val name = groupName.getOrElse(genGroupName().sample.get)
    val expr = groupType match {
      case GroupType.static => None
      case GroupType.dynamic => expression.orElse(Some(defaultExpression))
    }
    Post(Resource.uri(groupsApi), CreateGroup(name, groupType, expr))
  }

  def createStaticGroupOk(name: Name = genGroupName().sample.get): GroupId =
    createGroup(GroupType.static, None, Some(name)) ~> route ~> check {
      status shouldBe Created
      responseAs[GroupId]
    }

  def createDynamicGroupOk(expression: GroupExpression = defaultExpression, name: Name = genGroupName().sample.get): GroupId =
    createGroup(GroupType.dynamic, Some(expression), Some(name)) ~> route ~> check {
      status shouldBe Created
      responseAs[GroupId]
    }

  def createGroupOk(name: Name = genGroupName().sample.get): GroupId =
    if (Random.nextBoolean()) createStaticGroupOk(name) else createDynamicGroupOk(name = name)

  def addDeviceToGroup(groupId: GroupId, deviceUuid: DeviceId)(implicit ec: ExecutionContext): HttpRequest =
    Post(Resource.uri(groupsApi, groupId.show, "devices", deviceUuid.show))

  def addDeviceToGroupOk(groupId: GroupId, deviceUuid: DeviceId): Unit =
    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe OK
    }

  def removeDeviceFromGroup(groupId: GroupId, deviceId: DeviceId)(implicit ec: ExecutionContext): HttpRequest =
    Delete(Resource.uri(groupsApi, groupId.show, "devices", deviceId.show))

  def renameGroup(groupId: GroupId, newGroupName: Name)(implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri(groupsApi, groupId.show, "rename").withQuery(Query("groupName" -> newGroupName.value)))
}
