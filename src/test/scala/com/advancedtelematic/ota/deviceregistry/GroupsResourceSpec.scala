/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.{Group, Uuid}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.FunSuite

class GroupsResourceSpec extends FunSuite with ResourceSpec {
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val limit = 30

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder
  private[this] implicit val GroupDecoder = deriveDecoder[Group]

  test("GET all groups lists all groups") {
    //TODO: PRO-1182 turn this back into a property when we can delete groups
    val groupNames = Gen.listOfN(10, arbitrary[Group.Name]).sample.get
    groupNames.foreach { groupName =>
      createGroupOk(groupName)
    }

    listGroups() ~> route ~> check {
      status shouldBe OK
      val responseGroups = responseAs[PaginationResult[Group]]
      responseGroups.total shouldBe groupNames.size
      responseGroups.values.foreach { group =>
        groupNames.count(name => name == group.groupName) shouldBe 1
      }
    }
  }

  test("can list devices with custom pagination limit") {
    val deviceNumber = 50
    val group        = genGroupInfo.sample.get
    val groupId      = createGroupOk(group.groupName)

    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds: Seq[Uuid] = deviceTs.map(createDeviceOk(_))

    deviceIds.foreach(deviceId => addDeviceToGroupOk(groupId, deviceId))

    listDevicesInGroup(groupId, limit = Some(limit)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Uuid]]
      result.values.length shouldBe limit
    }
  }

  test("can list devices with custom pagination limit and offset") {
    val offset       = 10
    val deviceNumber = 50
    val group        = genGroupInfo.sample.get
    val groupId      = createGroupOk(group.groupName)

    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds: Seq[Uuid] = deviceTs.map(createDeviceOk(_))

    deviceIds.foreach(deviceId => addDeviceToGroupOk(groupId, deviceId))

    val allDevices = listDevicesInGroup(groupId, limit = Some(deviceNumber)) ~> route ~> check {
      responseAs[PaginationResult[Uuid]].values
    }

    listDevicesInGroup(groupId, offset = Some(offset), limit = Some(limit)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Uuid]]
      result.values.length shouldBe limit
      allDevices.slice(offset, offset + limit) shouldEqual result.values
    }
  }

  test("Get group details gives group id and name.") {
    val groupName = genGroupName.sample.get
    val groupId   = createGroupOk(groupName)

    getGroupDetails(groupId) ~> route ~> check {
      status shouldBe OK
      val group: Group = responseAs[Group]
      group.id shouldBe groupId
      group.groupName shouldBe groupName
    }
  }

  test("Get group details of valid non-existing group fails with 404.") {
    val groupId = genGroupInfo.sample.get.id

    getGroupDetails(groupId) ~> route ~> check {
      status shouldBe NotFound
    }
  }

  test("Renaming groups") {
    val groupName    = genGroupName.sample.get
    val newGroupName = genGroupName.sample.get
    val groupId      = createGroupOk(groupName)

    renameGroup(groupId, newGroupName) ~> route ~> check {
      status shouldBe OK
    }

    listGroups() ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[Group]]
      groups.values.count(e => e.id.equals(groupId) && e.groupName.equals(newGroupName)) shouldBe 1
    }
  }

  test("counting devices should fail for non-existent groups") {
    countDevicesInGroup(GroupId.generate()) ~> route ~> check {
      status shouldBe NotFound
    }
  }

  test("adding devices to groups") {
    val groupName = genGroupName.sample.get
    val deviceId  = createDeviceOk(genDeviceT.sample.get)
    val groupId   = createGroupOk(groupName)

    addDeviceToGroup(groupId, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values.contains(deviceId) shouldBe true
    }
  }

  test("rename group to existing group returns bad request") {
    val groupAName = genGroupName.sample.get
    val groupBName = genGroupName.sample.get
    val groupAId   = createGroupOk(groupAName)
    val groupBId   = createGroupOk(groupBName)

    renameGroup(groupAId, groupBName) ~> route ~> check {
      status shouldBe Conflict
    }
  }

  test("removing devices from groups") {
    val groupName = genGroupName.sample.get
    val deviceId  = createDeviceOk(genDeviceT.sample.get)
    val groupId   = createGroupOk(groupName)

    addDeviceToGroup(groupId, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values.contains(deviceId) shouldBe true
    }

    removeDeviceFromGroup(groupId, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values.contains(deviceId) shouldBe false
    }
  }

}
