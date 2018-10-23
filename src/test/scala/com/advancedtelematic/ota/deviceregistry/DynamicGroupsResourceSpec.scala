package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import cats.syntax.show._
import com.advancedtelematic.libats.data.{ErrorCodes, ErrorRepresentation, PaginationResult}
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, ValidExpression}
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures._
import eu.timepit.refined.refineV
import cats.syntax.either._
import eu.timepit.refined.api.Refined
import Group._
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId

class DynamicGroupsResourceSpec extends FunSuite with ResourceSpec {

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder

  private[this] implicit val GroupDecoder = deriveDecoder[Group]

  implicit class DeviceIdToExpression(value: DeviceOemId) {
    def toValidExp: GroupExpression =
      refineV[ValidExpression](s"deviceid contains ${value.underlying}").valueOr(err => throw new IllegalArgumentException(err))
  }

  test("dynamic group gets created.") {
    val group = genStaticGroup.sample.get
    createDynamicGroupOk(group.groupName, Refined.unsafeApply("deviceid contains something"))
  }

  test("device gets added to dynamic group") {
    val group      = genStaticGroup.sample.get
    val deviceT    = genDeviceT.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.toValidExp)

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values should have size 1
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("dynamic group is empty when no device matches the expression") {
    val group   = genStaticGroup.sample.get
    val groupId = createDynamicGroupOk(group.groupName, Refined.unsafeApply("deviceid contains nothing"))

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values should be(empty)
    }
  }

  test("dynamic group with invalid expression is not created") {
    val group      = genStaticGroup.sample.get
    val deviceT    = genDeviceT.sample.get

    createDeviceOk(deviceT)

    createGroup(group.groupName, GroupType.dynamic, Some(Refined.unsafeApply(""))) ~> route ~> check {
      status shouldBe BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidEntity
    }
  }

  test("manually adding a device to dynamic group fails") {
    val group      = genStaticGroup.sample.get
    val deviceT    = genDeviceT.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.toValidExp)

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("counts devices for dynamic group") {
    val group   = genStaticGroup.sample.get
    val deviceT = genDeviceT.sample.get
    val _       = createDeviceOk(deviceT)
    val groupId = createDynamicGroupOk(group.groupName, deviceT.deviceId.toValidExp)

    countDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[Long] shouldBe 1
    }
  }

  test("removing a device from a dynamic group fails") {
    val group      = genStaticGroup.sample.get
    val deviceT    = genDeviceT.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.toValidExp)

    removeDeviceFromGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("deleting a device causes it to be removed from dynamic group") {
    val group      = genStaticGroup.sample.get
    val deviceT    = genDeviceT.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.toValidExp)
    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceId]].values should contain(deviceUuid)
    }

    db.run(DeviceRepository.delete(group.namespace, deviceUuid)).futureValue

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceId]].values should be(empty)
    }
  }

  test("getting the groups of a device returns the correct dynamic groups") {
    val groupName1 = genGroupName().sample.get
    val groupName2 = genGroupName().sample.get

    val deviceT = genDeviceT.sample.get.copy(deviceName = Refined.unsafeApply("12347890800808"))
    val deviceId   = deviceT.deviceId
    val deviceUuid = createDeviceOk(deviceT)

    val expression1: GroupExpression = Refined.unsafeApply(s"deviceid contains ${deviceId.show.substring(0, 5)}") // To test "starts with"
    val groupId1    = createDynamicGroupOk(groupName1, expression1)
    val expression2: GroupExpression = Refined.unsafeApply(s"deviceid contains ${deviceId.show.substring(2, 10)}") // To test "contains"
    val groupId2    = createDynamicGroupOk(groupName2, expression2)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(groupId1)
      groups.values should contain(groupId2)
    }
  }

  test("getting the groups of a device using two 'contains' returns the correct dynamic groups") {
    val groupName1 = genGroupName().sample.get
    val groupName2 = genGroupName().sample.get

    val deviceT = genDeviceT.sample.get.copy(deviceName = Refined.unsafeApply("ABCDEFGHIJ"))
    val deviceId   = deviceT.deviceId
    val deviceUuid = createDeviceOk(deviceT)

    val expression1: GroupExpression = Refined.unsafeApply(s"deviceid contains ${deviceId.show.substring(0, 3)} and deviceid contains ${deviceId.show.substring(6, 9)}")
    val groupId1    = createDynamicGroupOk(groupName1, expression1)
    val expression2: GroupExpression = Refined.unsafeApply(s"deviceid contains 0empty0")
    val _    = createDynamicGroupOk(groupName2, expression2)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total shouldBe 1
      groups.values should contain(groupId1)
    }
  }

  test("getting the groups of a device returns the correct groups (dynamic and static)") {
    val deviceT = genDeviceT.sample.get.copy(deviceName = Refined.unsafeApply("ABCDE"))
    val deviceId   = deviceT.deviceId
    val deviceUuid = createDeviceOk(deviceT)

    // Add the device to a static group
    val staticGroup   = genGroupName().sample.get
    val staticGroupId = createStaticGroupOk(staticGroup)
    addDeviceToGroupOk(staticGroupId, deviceUuid)

    // Make the device show up for a dynamic group
    val dynamicGroup   = genGroupName().sample.get
    val expression: GroupExpression = Refined.unsafeApply(s"deviceid contains ${deviceId.show.substring(1, 5)}")
    val dynamicGroupId = createDynamicGroupOk(dynamicGroup, expression)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(staticGroupId)
      groups.values should contain(dynamicGroupId)
    }
  }
}
