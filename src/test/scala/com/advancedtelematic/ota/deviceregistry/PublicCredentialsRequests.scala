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

import java.util.Base64

import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import com.advancedtelematic.libats.data.ValidationError
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.PublicCredentialsResource.FetchPublicCredentials
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.DeviceName.validatedDeviceType

import scala.concurrent.ExecutionContext

trait PublicCredentialsRequests { self: ResourceSpec =>
  import StatusCodes._
  import com.advancedtelematic.ota.deviceregistry.data.Device._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val credentialsApi = "devices"

  private lazy val base64Decoder = Base64.getDecoder
  private lazy val base64Encoder = Base64.getEncoder

  def fetchPublicCredentials(device: DeviceId): HttpRequest = {
    import cats.syntax.show._
    Get(Resource.uri(credentialsApi, device.show, "public_credentials"))
  }

  def fetchPublicCredentialsOk(device: DeviceId): Array[Byte] =
    fetchPublicCredentials(device) ~> route ~> check {
      implicit val CredentialsDecoder = io.circe.generic.semiauto.deriveDecoder[FetchPublicCredentials]
      status shouldBe OK
      val resp = responseAs[FetchPublicCredentials]
      base64Decoder.decode(resp.credentials)
    }

  def createDeviceWithCredentials(devT: DeviceT)(implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri(credentialsApi), devT)

  def updatePublicCredentials(device: DeviceOemId, creds: Array[Byte], cType: Option[CredentialsType])
                             (implicit ec: ExecutionContext): HttpRequest = {
    val devT = validatedDeviceType.from(device.underlying)
      .map(DeviceT(None, _, device, DeviceType.Other, Some(base64Encoder.encodeToString(creds)), cType))
    createDeviceWithCredentials(devT.right.get)
  }

  def updatePublicCredentialsOk(device: DeviceOemId, creds: Array[Byte], cType: Option[CredentialsType] = None)
                               (implicit ec: ExecutionContext): DeviceId =
    updatePublicCredentials(device, creds, cType) ~> route ~> check {
      status shouldBe OK
      responseAs[DeviceId]
    }
}
