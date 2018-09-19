/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.Uri.Query
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupId, ValidName}
import com.advancedtelematic.ota.deviceregistry.data.{Group, SortBy, Uuid}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.PaginationResult
import eu.timepit.refined.api.Refined
import org.scalatest.FunSuite

class GroupsResourceSpec extends FunSuite with ResourceSpec {
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val limit = 30

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder
  private[this] implicit val GroupDecoder = deriveDecoder[Group]

  test("gets all existing groups") {
    //TODO: PRO-1182 turn this back into a property when we can delete groups
    val groupNames = Gen.listOfN(10, arbitrary[Group.Name]).sample.get
    groupNames.foreach { groupName =>
      createStaticGroupOk(groupName)
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

  test("gets all existing groups sorted by name") {
    val groupNames = Gen.listOfN(20, genGroupName(Gen.alphaNumChar)).sample.get
    val sortedGroupNames = groupNames.sortBy(_.value.toLowerCase)
    groupNames.map(createGroupOk)

    listGroups() ~> route ~> check {
      status shouldBe OK
      val responseGroups = responseAs[PaginationResult[Group]].values
      responseGroups.map(_.groupName).filter(sortedGroupNames.contains) shouldBe sortedGroupNames
    }
  }

  test("gets all existing groups sorted by creation time") {
    val groupNames = Gen.listOfN(20, genGroupName()).sample.get
    val groupIds = groupNames.map(createGroupOk)

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

  test("gets all existing groups matching a regular expression") {
    val names = Seq("aabb", "aaxbc", "a123b", "cba3")
    val groupNames = names.map(s => Refined.unsafeApply[String, ValidName](s))
    groupNames.map(createGroupOk)

    val tests = Map("a1" -> Seq("a123b"), "aa.*" -> Seq("aabb", "aaxbc"), "a\\d+[a-z]" -> Seq("a123b"), ".*3.*" -> Seq("a123b", "cba3"))

    tests.foreach{ case (k, v) =>
      searchGroups(Refined.unsafeApply(k)) ~> route ~> check {
        status shouldBe OK
        val responseGroupNames = responseAs[PaginationResult[Group]].values.map(_.groupName.value).filter(names.contains)
        responseGroupNames.size shouldBe v.size
        responseGroupNames should contain allElementsOf v
      }
    }
  }

  test("lists devices with custom pagination limit") {
    val deviceNumber = 50
    val group        = genStaticGroup.sample.get
    val groupId      = createStaticGroupOk(group.groupName)

    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds: Seq[Uuid] = deviceTs.map(createDeviceOk)

    deviceIds.foreach(deviceId => addDeviceToGroupOk(groupId, deviceId))

    listDevicesInGroup(groupId, limit = Some(limit)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Uuid]]
      result.values.length shouldBe limit
    }
  }

  test("lists devices with custom pagination limit and offset") {
    val offset       = 10
    val deviceNumber = 50
    val group        = genStaticGroup.sample.get
    val groupId      = createStaticGroupOk(group.groupName)

    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds: Seq[Uuid] = deviceTs.map(createDeviceOk)

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

  test("gets detailed information of a group") {
    val groupName = genGroupName().sample.get
    val groupId   = createStaticGroupOk(groupName)

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
    val groupName    = genGroupName().sample.get
    val newGroupName = genGroupName().sample.get
    val groupId      = createStaticGroupOk(groupName)

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
    val groupName  = genGroupName().sample.get
    val groupId    = createStaticGroupOk(groupName)
    val deviceUuid = createDeviceOk(genDeviceT.sample.get)

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("renaming a group to existing group fails") {
    val groupAName = genGroupName().sample.get
    val groupBName = genGroupName().sample.get
    val groupAId   = createStaticGroupOk(groupAName)
    val groupBId   = createStaticGroupOk(groupBName)

    renameGroup(groupAId, groupBName) ~> route ~> check {
      status shouldBe Conflict
    }
  }

  test("removes devices from a group") {
    val groupName = genGroupName().sample.get
    val deviceId  = createDeviceOk(genDeviceT.sample.get)
    val groupId   = createStaticGroupOk(groupName)

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
