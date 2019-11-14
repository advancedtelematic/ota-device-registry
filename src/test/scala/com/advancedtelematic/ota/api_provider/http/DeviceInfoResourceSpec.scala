package com.advancedtelematic.ota.api_provider.http

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{StatusCodes, Uri}
import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.ota.deviceregistry.{DeviceRequests, ResourceSpec}
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import cats.syntax.show._
import cats.syntax.either._
import com.advancedtelematic.libats.data.DataType.ValidChecksum
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId._
import com.advancedtelematic.libtuf.data.TufDataType.{ValidHardwareIdentifier, ValidTargetFilename}
import com.advancedtelematic.ota.api_provider.client.DirectorClient.{EcuInfoImage, Hashes}
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry

class DeviceInfoResourceSpec extends FunSuite with ResourceSpec with Eventually with DeviceRequests {

  import com.advancedtelematic.ota.api_provider.data.DataType._

  private def apiProviderUri(pathSuffixes: String*): Uri = {
    val BasePath = Path("/api-provider") / "api" / "v1alpha"
    Uri.Empty.withPath(pathSuffixes.foldLeft(BasePath)(_ / _))
  }

  test("sets different provider version header") {
    Get(apiProviderUri("devices"))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      response.headers.find(_.is("x-here-ota-api-provider-version")).map(_.value()) should contain("api-provider@device-registry")
    }
  }

  test("list devices, paginated") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    createDeviceOk(device)

    Get(apiProviderUri("devices"))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val pages = responseAs[PaginationResult[ListingDevice]]

      pages.limit shouldBe 50
      pages.offset shouldBe 0
      pages.total shouldBe 1

      val first = pages.values.head

      first shouldBe ListingDevice(device.uuid.get, device.deviceId)
    }
  }

  test("list devices, paginated, filtered by oem id") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    createDeviceOk(device)

    val device02 = genDeviceT.retryUntil(_.uuid.isDefined).generate
    createDeviceOk(device02)

    Get(apiProviderUri("devices").withRawQueryString(s"oemId=${device.deviceId.show}"))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val pages = responseAs[PaginationResult[ListingDevice]]

      pages.limit shouldBe 50
      pages.offset shouldBe 0
      pages.total shouldBe 1

      val first = pages.values.head

      first shouldBe ListingDevice(device.uuid.get, device.deviceId)
    }
  }


  test("gets information for a device") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    val ecuId = EcuIdentifier("somefakeid").valueOr(throw _)
    val hardwareIdentifier = "fakehwid".refineTry[ValidHardwareIdentifier].get
    val targetFilename = "some-hash".refineTry[ValidTargetFilename].get
    val hash = "848cba347e8a37330b97835936dd4f846291739d0d5efa9eb10c75e4c15ba87a".refineTry[ValidChecksum].get
    val image = EcuInfoImage(targetFilename, 2222, Hashes(hash))

    directorClient.addDevice(deviceId, ecuId, hardwareIdentifier, image)

    Get(apiProviderUri("devices", deviceId.show))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val apiDevice = responseAs[ApiDevice]
      apiDevice.oemId shouldBe device.deviceId
      apiDevice.id shouldBe device.uuid.get
      apiDevice.lastSeen shouldBe None
      apiDevice.status shouldBe DeviceStatus.NotSeen

      apiDevice.primaryEcu shouldBe defined
      apiDevice.primaryEcu.map(_.ecuId) should contain(ecuId)
      apiDevice.primaryEcu.map(_.installedTarget.filename) should contain(targetFilename)
      apiDevice.primaryEcu.map(_.installedTarget.target.length) should contain(2222)
      apiDevice.primaryEcu.map(_.installedTarget.target.hashes.head._2) should contain(hash)
    }
  }

  test("returns empty primary ecu info if director returns 404") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    Get(apiProviderUri("devices", deviceId.show)) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val apiDevice = responseAs[ApiDevice]
      apiDevice.oemId shouldBe device.deviceId
      apiDevice.id shouldBe device.uuid.get
      apiDevice.lastSeen shouldBe None
      apiDevice.status shouldBe DeviceStatus.NotSeen

      apiDevice.primaryEcu shouldBe empty
    }
  }
}
