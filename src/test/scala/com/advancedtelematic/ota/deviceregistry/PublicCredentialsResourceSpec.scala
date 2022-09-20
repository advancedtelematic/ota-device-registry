/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.ota.deviceregistry.data.{CredentialsType, Device}
import com.advancedtelematic.ota.deviceregistry.PublicCredentialsResource.FetchPublicCredentials
import io.circe.generic.auto._
import org.scalacheck.{Arbitrary, Gen}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT

class PublicCredentialsResourceSpec extends ResourcePropSpec {
  import Device._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  val genCredentialsType: Gen[CredentialsType.CredentialsType] =
    Gen.oneOf(CredentialsType.values.toSeq)

  implicit lazy val arbCredentialsType: Arbitrary[CredentialsType.CredentialsType] = Arbitrary(
    genCredentialsType
  )

  property("GET requests fails on non-existent device") {
    forAll { uuid: DeviceId =>
      fetchPublicCredentials(uuid) ~> route ~> check { status shouldBe NotFound }
    }
  }

  property("GET request after PUT yields same credentials") {
    forAll { (deviceId: DeviceOemId, creds: Array[Byte]) =>
      val uuid = updatePublicCredentialsOk(deviceId, creds)

      fetchPublicCredentialsOk(uuid) shouldBe creds
    }
  }

  property("PUT uses existing uuid if device exists") {
    forAll { (devId: DeviceOemId, mdevT: DeviceT, creds: Array[Byte]) =>
      val devT = mdevT.copy(deviceId = devId)
      val uuid: DeviceId = createDeviceOk(devT)
      uuid shouldBe updatePublicCredentialsOk(devId, creds)

      // updatePublicCredentials didn't change the device
      fetchDevice(uuid) ~> route ~> check {
        status shouldBe OK
        val dev = responseAs[Device]
        dev.deviceName shouldBe devT.deviceName
        dev.deviceId shouldBe devT.deviceId
        dev.deviceType shouldBe devT.deviceType
      }
    }
  }

  property("Latest PUT is the one that wins") {
    forAll { (deviceId: DeviceOemId, creds1: Array[Byte], creds2: Array[Byte]) =>
      val uuid = updatePublicCredentialsOk(deviceId, creds1)
      updatePublicCredentialsOk(deviceId, creds2)

      fetchPublicCredentialsOk(uuid) shouldBe creds2
    }
  }

  property("Type of credentials is set correctly") {
    forAll { (deviceId: DeviceOemId, mdevT: DeviceT, creds: String, cType: CredentialsType.CredentialsType) =>
      val devT = mdevT.copy(deviceId = deviceId, credentials = Some(creds), credentialsType = Some(cType))
      val uuid: DeviceId = createDeviceWithCredentials(devT) ~> route ~> check {
        status shouldBe OK
        responseAs[DeviceId]
      }

      fetchPublicCredentials(uuid) ~> route ~> check {
        status shouldBe OK
        val dev = responseAs[FetchPublicCredentials]

        dev.uuid shouldBe uuid
        dev.credentialsType shouldBe cType
        dev.credentials shouldBe creds
      }
    }
  }
}
