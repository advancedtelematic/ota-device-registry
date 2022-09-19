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

package com.advancedtelematic.ota.deviceregistry.data

import java.time.Instant

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.DeviceName.validatedDeviceType
import com.advancedtelematic.ota.deviceregistry.data.TagId.validatedTagId
import com.advancedtelematic.ota.deviceregistry.data.Namespaces.defaultNs
import org.scalacheck.{Arbitrary, Gen}

trait DeviceGenerators {

  import Arbitrary._
  import Device._

  val genDeviceName: Gen[DeviceName] = for {
    //use a minimum length for DeviceName to reduce possibility of naming conflicts
    size <- Gen.choose(50, 200)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield validatedDeviceType.from(name.mkString).right.get

  val genDeviceUUID: Gen[DeviceId] = Gen.delay(DeviceId.generate)

  val genDeviceId: Gen[DeviceOemId] = for {
    size <- Gen.choose(10, 100)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield DeviceOemId(name.mkString)

  val genDeviceType: Gen[DeviceType] = for {
    t <- Gen.oneOf(DeviceType.values.toSeq)
  } yield t

  val genInstant: Gen[Instant] = for {
    millis <- Gen.chooseNum[Long](0, 10000000000000L)
  } yield Instant.ofEpochMilli(millis)

  def genDeviceWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceOemId]): Gen[Device] =
    for {
      uuid       <- genDeviceUUID
      name       <- deviceNameGen
      deviceId   <- deviceIdGen
      deviceType <- genDeviceType
      lastSeen   <- Gen.option(genInstant)
      activated  <- Gen.option(genInstant)
    } yield Device(defaultNs, uuid, name, deviceId, deviceType, lastSeen, Instant.now(), activated)

  val genDevice: Gen[Device] = genDeviceWith(genDeviceName, genDeviceId)

  def genDeviceTWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceOemId]): Gen[DeviceT] =
    for {
      uuid       <- Gen.option(genDeviceUUID)
      name       <- deviceNameGen
      deviceId   <- deviceIdGen
      deviceType <- genDeviceType
    } yield DeviceT(uuid, name, deviceId, deviceType)

  val genDeviceT: Gen[DeviceT] = genDeviceTWith(genDeviceName, genDeviceId)

  def genConflictFreeDeviceTs(): Gen[Seq[DeviceT]] =
    genConflictFreeDeviceTs(arbitrary[Int].sample.get)

  def genConflictFreeDeviceTs(n: Int): Gen[Seq[DeviceT]] =
    for {
      dns  <- Gen.containerOfN[Seq, DeviceName](n, genDeviceName)
      dids <- Gen.containerOfN[Seq, DeviceOemId](n, genDeviceId)
    } yield {
      dns.zip(dids).map {
        case (nameG, idG) =>
          genDeviceTWith(nameG, idG).sample.get
      }
    }

  implicit lazy val arbDeviceName: Arbitrary[DeviceName] = Arbitrary(genDeviceName)
  implicit lazy val arbDeviceUUID: Arbitrary[DeviceId] = Arbitrary(genDeviceUUID)
  implicit lazy val arbDeviceId: Arbitrary[DeviceOemId]     = Arbitrary(genDeviceId)
  implicit lazy val arbDeviceType: Arbitrary[DeviceType] = Arbitrary(genDeviceType)
  implicit lazy val arbLastSeen: Arbitrary[Instant]      = Arbitrary(genInstant)
  implicit lazy val arbDevice: Arbitrary[Device]         = Arbitrary(genDevice)
  implicit lazy val arbDeviceT: Arbitrary[DeviceT]       = Arbitrary(genDeviceT)

}

object DeviceGenerators extends DeviceGenerators

object InvalidDeviceGenerators extends DeviceGenerators with DeviceIdGenerators {
  val genInvalidVehicle: Gen[Device] = for {
    // TODO: for now, just generate an invalid VIN with a valid namespace
    deviceId <- genInvalidDeviceId
    d        <- genDevice
  } yield d.copy(deviceId = deviceId, namespace = defaultNs)

  def getInvalidVehicle: Device = genInvalidVehicle.sample.getOrElse(getInvalidVehicle)
}
