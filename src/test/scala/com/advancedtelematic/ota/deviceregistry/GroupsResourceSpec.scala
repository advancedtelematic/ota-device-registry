/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.Uri.Query
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupName, SortBy}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.{ErrorCodes, ErrorRepresentation, Limit, Offset, PaginationResult}
import com.advancedtelematic.ota.deviceregistry.common.Errors.Codes.MalformedInput
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

class GroupsResourceSpec extends FunSuite with ResourceSpec with ScalaFutures {
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val limit = 30

  test("gets all existing groups") {
    //TODO: PRO-1182 turn this back into a property when we can delete groups
    val groupNames = Gen.listOfN(10, arbitrary[GroupName]).sample.get
    groupNames.foreach(createStaticGroupOk)

    listGroups() ~> route ~> check {
      status shouldBe OK
      val responseGroups = responseAs[PaginationResult[Group]]
      responseGroups.total shouldBe groupNames.size
      responseGroups.values.foreach { group =>
        groupNames.count(name => name == group.groupName) shouldBe 1
      }
    }
  }

  test("gets all existing groups sorted by name") {
    val groupNames = Gen.listOfN(20, genGroupName(Gen.alphaNumChar)).sample.get
    val sortedGroupNames = groupNames.sortBy(_.value.toLowerCase)
    groupNames.foreach(n => createGroupOk(n))

    listGroups() ~> route ~> check {
      status shouldBe OK
      val responseGroups = responseAs[PaginationResult[Group]].values
      responseGroups.map(_.groupName).filter(sortedGroupNames.contains) shouldBe sortedGroupNames
    }
  }

  test("gets all existing groups sorted by creation time") {
    val groupIds = (1 to 20).map(_ => createGroupOk())

    listGroups(Some(SortBy.CreatedAt)) ~> route ~> check {
      status shouldBe OK
      val responseGroups = responseAs[PaginationResult[Group]].values
      responseGroups.reverse.map(_.id).filter(groupIds.contains) shouldBe groupIds
    }
  }

  test("fails to get existing groups given an invalid sorting") {
    val q = Query(Map("sortBy" -> Gen.alphaNumStr.sample.get))
    Get(Resource.uri(groupsApi).withQuery(q)) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("gets all existing groups that contain a string") {
    val names = Seq("aabb", "baaxbc", "a123ba", "cba3b")
    val groupNames = names.map(GroupName(_).right.get)
    groupNames.foreach(n => createGroupOk(n))

    val tests = Map("" -> names, "a1" -> Seq("a123ba"), "aa" -> Seq("aabb", "baaxbc"), "3b" -> Seq("a123ba", "cba3b"), "3" -> Seq("a123ba", "cba3b"))

    tests.foreach{ case (k, v) =>
      listGroups(nameContains = Some(k)) ~> route ~> check {
        status shouldBe OK
        val responseGroupNames = responseAs[PaginationResult[Group]].values.map(_.groupName.value).filter(names.contains)
        responseGroupNames.size shouldBe v.size
        responseGroupNames should contain allElementsOf v
      }
    }
  }

  test("lists devices with custom pagination limit") {
    val deviceNumber = 50
    val groupId      = createStaticGroupOk()

    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds = deviceTs.map(createDeviceOk)

    deviceIds.foreach(deviceId => addDeviceToGroupOk(groupId, deviceId))

    listDevicesInGroup(groupId, limit = Some(limit)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[DeviceId]]
      result.values.length shouldBe limit
    }
  }

  test("lists devices with custom pagination limit and offset") {
    val offset       = 10
    val deviceNumber = 50
    val groupId      = createStaticGroupOk()

    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds = deviceTs.map(createDeviceOk)

    deviceIds.foreach(deviceId => addDeviceToGroupOk(groupId, deviceId))

    val allDevices = listDevicesInGroup(groupId, limit = Some(deviceNumber)) ~> route ~> check {
      responseAs[PaginationResult[DeviceId]].values
    }

    listDevicesInGroup(groupId, offset = Some(offset), limit = Some(limit)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[DeviceId]]
      result.values.length shouldBe limit
      allDevices.slice(offset, offset + limit) shouldEqual result.values
    }
  }

  test("lists devices with negative pagination limit fails") {
    val groupId = createStaticGroupOk()

    listDevicesInGroup(groupId, limit = Some(-1)) ~> route ~> check {
      status shouldBe BadRequest
      val res = responseAs[ErrorRepresentation]
      res.code shouldBe ErrorCodes.InvalidEntity
      res.description should include("The query parameter 'limit' was malformed")
    }
  }

  test("lists devices with negative pagination offset fails") {
    val groupId = createStaticGroupOk()

    listDevicesInGroup(groupId, offset = Some(-1)) ~> route ~> check {
      status shouldBe BadRequest
      val res = responseAs[ErrorRepresentation]
      res.code shouldBe ErrorCodes.InvalidEntity
      res.description should include("The query parameter 'offset' was malformed")
    }
  }

  test("gets detailed information of a group") {
    val groupName = genGroupName().sample.get
    val groupId      = createStaticGroupOk(groupName)

    getGroupDetails(groupId) ~> route ~> check {
      status shouldBe OK
      val group: Group = responseAs[Group]
      group.id shouldBe groupId
      group.groupName shouldBe groupName
    }
  }

  test("gets detailed information of a non-existing group fails") {
    val groupId = genStaticGroup.sample.get.id

    getGroupDetails(groupId) ~> route ~> check {
      status shouldBe NotFound
    }
  }

  test("renames a group") {
    val newGroupName = genGroupName().sample.get
    val groupId      = createStaticGroupOk()

    renameGroup(groupId, newGroupName) ~> route ~> check {
      status shouldBe OK
    }

    listGroups(limit = Some(100L)) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[Group]]
      groups.values.count(e => e.id.equals(groupId) && e.groupName.equals(newGroupName)) shouldBe 1
    }
  }

  test("counting devices fails for non-existing groups") {
    countDevicesInGroup(GroupId.generate()) ~> route ~> check {
      status shouldBe NotFound
    }
  }

  test("adds devices to groups") {
    val groupId    = createStaticGroupOk()
    val deviceUuid = createDeviceOk(genDeviceT.sample.get)

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("renaming a group to existing group fails") {
    val groupBName = genGroupName().sample.get
    val groupAId   = createStaticGroupOk()
    val _   = createStaticGroupOk(groupBName)

    renameGroup(groupAId, groupBName) ~> route ~> check {
      status shouldBe Conflict
    }
  }

  test("removes devices from a group") {
    val deviceId  = createDeviceOk(genDeviceT.sample.get)
    val groupId   = createStaticGroupOk()

    addDeviceToGroup(groupId, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values.contains(deviceId) shouldBe true
    }

    removeDeviceFromGroup(groupId, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values.contains(deviceId) shouldBe false
    }
  }

  test("creates a static group from a file") {
    val groupName = genGroupName().sample.get
    val deviceTs = Gen.listOf(genDeviceT).sample.get
    val uuidsCreated = deviceTs.map(createDeviceOk)

    importGroup(groupName, deviceTs.map(_.deviceId)) ~> route ~> check {
      status shouldEqual Created
      val groupId = responseAs[GroupId]
      val uuidsInGroup = new GroupMembership().listDevices(groupId, Some(Offset(0L)), Some(Limit(deviceTs.size.toLong))).futureValue.values
      uuidsInGroup should contain allElementsOf uuidsCreated
    }
  }

  test("creates a static group from a file even when containing more than FILTER_EXISTING_DEVICES_BATCH_SIZE deviceIds") {
    val groupName = genGroupName().sample.get
    val deviceTs = Gen.listOfN(500, genDeviceT).sample.get
    val uuidsCreated = deviceTs.map(createDeviceOk)

    importGroup(groupName, deviceTs.map(_.deviceId)) ~> route ~> check {
      status shouldEqual Created
      val groupId = responseAs[GroupId]
      val uuidsInGroup = new GroupMembership().listDevices(groupId, Some(Offset(0L)), Some(Limit(deviceTs.size.toLong))).futureValue.values
      uuidsInGroup should contain allElementsOf uuidsCreated
    }
  }

  test("creates a static group from a file but doesn't add devices if they're not provisioned") {
    val groupName = genGroupName().sample.get
    val deviceTs = Gen.listOf(genDeviceT).sample.get

    importGroup(groupName, deviceTs.map(_.deviceId)) ~> route ~> check {
      status shouldEqual Created
      val groupId = responseAs[GroupId]
      val uuidsInGroup = new GroupMembership().listDevices(groupId, Some(Offset(0L)), Some(Limit(deviceTs.size.toLong))).futureValue.values
      uuidsInGroup shouldBe empty
    }
  }

  test("creating a static group from a file fails with 400 if the deviceIds are longer than it's allowed") {
    val groupName = genGroupName().sample.get
    val oemId = Gen.listOfN(130, Gen.alphaNumChar).map(_.mkString).map(DeviceOemId).sample.get

    importGroup(groupName, Seq(oemId)) ~> route ~> check {
      status shouldEqual BadRequest
      responseAs[ErrorRepresentation].code shouldBe MalformedInput
    }
  }

}
