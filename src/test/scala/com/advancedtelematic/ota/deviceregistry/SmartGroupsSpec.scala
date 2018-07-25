package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.data.{Group, Uuid}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.FunSuite

class SmartGroupsSpec extends FunSuite with ResourceSpec {

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder
  private[this] implicit val GroupDecoder = deriveDecoder[Group]

  test("can create a smart group") {
    val group = genGroupInfo.sample.get
    createDynamicGroupOk(group.groupName, "")
  }

  test("device gets added to smart group") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.underlying)

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values should have size (1)
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("smart group should return empty when no device matches") {
    val group   = genGroupInfo.sample.get
    val groupId = createDynamicGroupOk(group.groupName, "   ")

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values should be(empty)
    }
  }

  test("smart group  does not return devices that do not match when using empty expression") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, "")
    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values shouldNot contain(deviceUuid)
    }
  }

  test("adding a device to smart group fails") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, "")

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("counts devices for dynamic group") {
    val group   = genGroupInfo.sample.get
    val deviceT = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val _       = createDeviceOk(deviceT)
    val groupId = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.underlying)

    countDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[Long] shouldBe 1
    }
  }

  test("removing a device from a smart group fails") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.underlying)
    removeDeviceFromGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

}
