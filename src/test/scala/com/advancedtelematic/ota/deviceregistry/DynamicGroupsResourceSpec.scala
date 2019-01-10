package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import cats.syntax.either._
import cats.syntax.show._
import com.advancedtelematic.libats.data.{ErrorCodes, ErrorRepresentation, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, ValidExpression, _}
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import io.circe.Decoder
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar._

class DynamicGroupsResourceSpec extends FunSuite with ResourceSpec with Eventually {

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder

  private[this] implicit val GroupDecoder: Decoder[Group] = deriveDecoder[Group]

  implicit class DeviceIdToExpression(value: DeviceOemId) {
    def toValidExp: GroupExpression =
      refineV[ValidExpression](s"deviceid contains ${value.underlying}").valueOr(err => throw new IllegalArgumentException(err))
  }

  test("dynamic group gets created.") {
    createGroup(GroupType.dynamic, Some(Refined.unsafeApply("deviceid contains something")), None) ~> route ~> check {
      status shouldBe Created
    }
  }

  test("device gets added to dynamic group") {
    val deviceT    = genCreateDevice.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(deviceT.deviceId.toValidExp)

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values should have size 1
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("dynamic group is empty when no device matches the expression") {
    val groupId    = createDynamicGroupOk(Refined.unsafeApply("deviceid contains nothing"))

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[DeviceId]]
      devices.values should be(empty)
    }
  }

  test("dynamic group with invalid expression is not created") {
    createGroup(GroupType.dynamic, Some(Refined.unsafeApply("")), None) ~> route ~> check {
      status shouldBe BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.InvalidEntity
    }
  }

  test("manually adding a device to dynamic group fails") {
    val deviceT    = genCreateDevice.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(deviceT.deviceId.toValidExp)

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("counts devices for dynamic group") {
    val deviceT = genCreateDevice.sample.get
    val _       = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(deviceT.deviceId.toValidExp)

    countDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[Long] shouldBe 1
    }
  }

  test("removing a device from a dynamic group fails") {
    val deviceT    = genCreateDevice.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(deviceT.deviceId.toValidExp)

    removeDeviceFromGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("deleting a device causes it to be removed from dynamic group") {
    val deviceT    = genCreateDevice.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(deviceT.deviceId.toValidExp)
    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceId]].values should contain(deviceUuid)
    }

    db.run(DeviceRepository.delete(defaultNs, deviceUuid))

    eventually(timeout(5.seconds), interval(100.millis)) {
      listDevicesInGroup(groupId) ~> route ~> check {
        status shouldBe OK
        responseAs[PaginationResult[DeviceId]].values should be(empty)
      }
    }
  }

  test("getting the groups of a device returns the correct dynamic groups") {
    val deviceT = genCreateDevice.sample.get.copy(deviceName = Refined.unsafeApply("12347890800808"))
    val deviceId: DeviceOemId = deviceT.deviceId
    val deviceUuid = createDeviceOk(deviceT)

    val expression1 = Refined.unsafeApply[String, ValidExpression](s"deviceid contains ${deviceId.show.substring(0, 5)}") // To test "starts with"
    val groupId1    = createDynamicGroupOk(expression1)
    val expression2 = Refined.unsafeApply[String, ValidExpression](s"deviceid contains ${deviceId.show.substring(2, 10)}") // To test "contains"
    val groupId2    = createDynamicGroupOk(expression2)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(groupId1)
      groups.values should contain(groupId2)
    }
  }

  test("getting the groups of a device using two 'contains' returns the correct dynamic groups") {
    val deviceT = genCreateDevice.sample.get.copy(deviceName = Refined.unsafeApply("ABCDEFGHIJ"))
    val deviceId: DeviceOemId = deviceT.deviceId
    val deviceUuid = createDeviceOk(deviceT)

    val expression1 = Refined.unsafeApply[String, ValidExpression](s"deviceid contains ${deviceId.show.substring(0, 3)} and deviceid contains ${deviceId.show.substring(6, 9)}")
    val groupId1    = createDynamicGroupOk(expression1)
    val expression2 = Refined.unsafeApply[String, ValidExpression](s"deviceid contains 0empty0")
    val _    = createDynamicGroupOk(expression2)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total shouldBe 1
      groups.values should contain(groupId1)
    }
  }

  test("getting the groups of a device returns the correct groups (dynamic and static)") {
    val deviceT = genCreateDevice.sample.get.copy(deviceName = Refined.unsafeApply("ABCDE"))
    val deviceUuid = createDeviceOk(deviceT)

    // Add the device to a static group
    val staticGroupId = createStaticGroupOk()
    addDeviceToGroupOk(staticGroupId, deviceUuid)

    // Make the device show up for a dynamic group
    val expression = Refined.unsafeApply[String, ValidExpression](s"deviceid contains ${deviceT.deviceId.show.substring(1, 5)}")
    val dynamicGroupId = createDynamicGroupOk(expression)

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(staticGroupId)
      groups.values should contain(dynamicGroupId)
    }
  }
}
