package com.advancedtelematic.ota.api_provider.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.ota.deviceregistry.{DeviceRequests, ResourceSpec}
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually


class ApiDocsResourceSpec extends FunSuite with ResourceSpec with Eventually with DeviceRequests {

  test("returns yml definition file") {
    Get("/api-provider/docs/definition.yml")  ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  test("returns swagger file") {
    Get("/api-provider/docs")  ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }
}
