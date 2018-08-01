package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import cats.syntax.show._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, ValidExpression}
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupInfoRepository}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures._
import eu.timepit.refined.refineV
import cats.syntax.either._
import eu.timepit.refined.api.Refined
import Group._

class DynamicGroupsSpec extends FunSuite with ResourceSpec {

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder

  private[this] implicit val GroupDecoder = deriveDecoder[Group]

  implicit class DeviceIdToExpression(value: String) {
    def toValidExp: GroupExpression =
      refineV[ValidExpression](value).valueOr(err => throw new IllegalArgumentException(err))
  }

  test("dynamic group gets created.") {
    val group = genGroupInfo.sample.get
    createDynamicGroupOk(group.groupName, Refined.unsafeApply("valid exp"))
  }

  test("device gets added to dynamic group") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.show.toValidExp)

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values should have size 1
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("dynamic group is empty when no device matches the expression") {
    val group   = genGroupInfo.sample.get
    val groupId = createDynamicGroupOk(group.groupName, Refined.unsafeApply("valid expression that doesnt match"))

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values should be(empty)
    }
  }

  test("dynamic group with invalid expression is not created") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    createGroup(group.groupName, GroupType.dynamic, Some(Refined.unsafeApply(""))) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("manually adding a device to dynamic group fails") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.show.toValidExp)

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("counts devices for dynamic group") {
    val group   = genGroupInfo.sample.get
    val deviceT = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val _       = createDeviceOk(deviceT)
    val groupId = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.show.toValidExp)

    countDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[Long] shouldBe 1
    }
  }

  test("removing a device from a dynamic group fails") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.show.toValidExp)
    removeDeviceFromGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("deleting a device causes it to be removed from dynamic group") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.show.toValidExp)
    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[Uuid]].values should contain(deviceUuid)
    }

    db.run(DeviceRepository.delete(group.namespace, deviceUuid)).futureValue

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[Uuid]].values should be(empty)
    }
  }

  test("getting the groups of a device returns the correct dynamic groups") {
    val groupName1 = genGroupName.sample.get
    val groupName2 = genGroupName.sample.get

    val deviceT = genDeviceT
      .retryUntil(d => d.deviceId.isDefined && d.deviceId.get.show.length > 9)
      .sample
      .get
    val deviceId   = deviceT.deviceId.get
    val deviceUuid = createDeviceOk(deviceT)

    val expression1 = deviceId.show.substring(0, 5).toValidExp // To test "starts with"
    val groupId1    = createDynamicGroupOk(groupName1, expression1)
    val expression2 = deviceId.show.substring(2, 10).toValidExp // To test "contains"
    val groupId2    = createDynamicGroupOk(groupName2, expression2)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(groupId1)
      groups.values should contain(groupId2)
    }
  }

  test("getting the groups of a device returns the correct groups (dynamic and static)") {
    val deviceT = genDeviceT
      .retryUntil(d => d.deviceId.isDefined && d.deviceId.get.show.length > 4)
      .sample
      .get
    val deviceId   = deviceT.deviceId.get
    val deviceUuid = createDeviceOk(deviceT)

    // Add the device to a static group
    val staticGroup   = genGroupName.sample.get
    val staticGroupId = createGroupOk(staticGroup)
    addDeviceToGroupOk(staticGroupId, deviceUuid)

    // Make the device show up for a dynamic group
    val dynamicGroup   = genGroupName.sample.get
    val expression     = deviceId.show.substring(1, 5).toValidExp
    val dynamicGroupId = createDynamicGroupOk(dynamicGroup, expression)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(staticGroupId)
      groups.values should contain(dynamicGroupId)
    }
  }

  test("renaming a device id should remove it from dynamic group") {
    val deviceT    = genDeviceT.retryUntil(d => d.deviceId.isDefined && d.deviceId.get.show.length > 4).sample.get
    val deviceId   = deviceT.deviceId.get
    val deviceUuid = createDeviceOk(deviceT)

    val dynamicGroup   = genGroupName.sample.get
    val expression     = deviceId.show.substring(1, 5).toValidExp
    val dynamicGroupId = createDynamicGroupOk(dynamicGroup, expression)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total shouldBe 1
      groups.values should contain(dynamicGroupId)
    }

    updateDeviceOk(deviceUuid, deviceT.copy(deviceId = None))

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total shouldBe 0
    }

  }

}
