package com.advancedtelematic.ota.api_translator.http

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.syntax.show._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.{DeviceRequests, ResourceSpec}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually

class DeviceInfoResourceSpec extends FunSuite with ResourceSpec with Eventually with DeviceRequests {

  import com.advancedtelematic.ota.api_translator.data.DataType._

  def apiTranslateUri(pathSuffixes: String*): Uri = {
    val BasePath = Path("/api-translator") / "api" / "v1"
    Uri.Empty.withPath(pathSuffixes.foldLeft(BasePath)(_ / _))
  }

  test("sets different x-ats-version header") {
    pending
  }

  test("list devices, paginated") {
    val device = genDeviceT.generate
    createDeviceOk(device)

    Get(apiTranslateUri("devices"))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val pages = responseAs[PaginationResult[ApiDevice]]

      pages.limit shouldBe 50
      pages.offset shouldBe 0
      pages.total shouldBe 1

      val first = pages.values.head

      first.oemId shouldBe device.deviceId
      first.id shouldBe device.uuid.get
    }
  }

  test("gets information for a device") {
    val device = genDeviceT.generate
    val deviceId = createDeviceOk(device)

    Get(apiTranslateUri("devices", deviceId.show))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val apiDevice = responseAs[ApiDevice]
      apiDevice.oemId shouldBe device.deviceId
      apiDevice.id shouldBe device.uuid.get
      apiDevice.lastSeen shouldBe None
      apiDevice.status shouldBe DeviceStatus.NotSeen // TODO: Probably not accurate, what do we do?
    }
  }
}
