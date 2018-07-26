package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.{ErrorRepresentation, PaginationResult}
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, ValidExpression}
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures._
import eu.timepit.refined.refineV
import cats.syntax.either._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceId
import eu.timepit.refined.api.Refined
import Group._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException

class DynamicGroupsSpec extends FunSuite with ResourceSpec {

  import com.advancedtelematic.libats.codecs.CirceCodecs._
  import io.circe.generic.semiauto.deriveDecoder

  private[this] implicit val GroupDecoder = deriveDecoder[Group]

  implicit class DeviceIdToExpression(value: DeviceId) {
    def toValidExp: GroupExpression =
      refineV[ValidExpression](value.underlying).valueOr(err => throw new IllegalArgumentException(err))
  }

  test("can create a smart group") {
    val group = genGroupInfo.sample.get
    createDynamicGroupOk(group.groupName, Refined.unsafeApply("valid exp"))
  }

  test("device gets added to smart group") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.toValidExp)

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values should have size 1
      devices.values.contains(deviceUuid) shouldBe true
    }
  }

  test("smart group should return empty when no device matches") {
    val group   = genGroupInfo.sample.get
    val groupId = createDynamicGroupOk(group.groupName, Refined.unsafeApply("valid expression that doesnt match"))

    listDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Uuid]]
      devices.values should be(empty)
    }
  }

  test("XXX cannot create smart group with invalid expression") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    createGroup(group.groupName, GroupType.dynamic, Some(Refined.unsafeApply(""))) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("adding a device to smart group fails") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.toValidExp)

    addDeviceToGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("counts devices for dynamic group") {
    val group   = genGroupInfo.sample.get
    val deviceT = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val _       = createDeviceOk(deviceT)
    val groupId = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.toValidExp)

    countDevicesInGroup(groupId) ~> route ~> check {
      status shouldBe OK
      responseAs[Long] shouldBe 1
    }
  }

  test("removing a device from a smart group fails") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.toValidExp)
    removeDeviceFromGroup(groupId, deviceUuid) ~> route ~> check {
      status shouldBe BadRequest
    }
  }

  test("deleting a device causes it to be removed from dynamic group") {
    val group      = genGroupInfo.sample.get
    val deviceT    = genDeviceT.retryUntil(_.deviceId.isDefined).sample.get
    val deviceUuid = createDeviceOk(deviceT)
    val groupId    = createDynamicGroupOk(group.groupName, deviceT.deviceId.get.toValidExp)
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
}
