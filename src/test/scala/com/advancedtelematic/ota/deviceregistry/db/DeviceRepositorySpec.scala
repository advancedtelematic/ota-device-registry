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

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import com.advancedtelematic.libats.test.{DatabaseSpec, LongTest}
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.data.DeviceGenerators.{genDeviceId, genDeviceT}
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeletedDevice
import com.advancedtelematic.ota.deviceregistry.data.Namespaces
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.LoneElement._
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FunSuite, Matchers}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global

class DeviceRepositorySpec extends FunSuite with DatabaseSpec with ScalaFutures with Matchers with LongTest {

  test("updateLastSeen sets activated_at the first time only") {

    val device = genDeviceT.sample.get.copy(deviceId = genDeviceId.sample.get)
    val setTwice = for {
      uuid   <- DeviceRepository.create(Namespaces.defaultNs, device)
      first  <- DeviceRepository.updateLastSeen(uuid, Instant.now()).map(_._1)
      second <- DeviceRepository.updateLastSeen(uuid, Instant.now()).map(_._1)
    } yield (first, second)

    whenReady(db.run(setTwice), Timeout(Span(10, Seconds))) {
      case (f, s) =>
        f shouldBe true
        s shouldBe false
    }
  }

  test("activated_at can be counted") {

    val device = genDeviceT.sample.get.copy(deviceId = genDeviceId.sample.get)
    val createDevice = for {
      uuid <- DeviceRepository.create(Namespaces.defaultNs, device)
      now = Instant.now()
      _     <- DeviceRepository.updateLastSeen(uuid, now)
      count <- DeviceRepository.countActivatedDevices(Namespaces.defaultNs, now, now.plusSeconds(100))
    } yield count

    whenReady(db.run(createDevice), Timeout(Span(10, Seconds))) { count =>
      count shouldBe 1
    }
  }

  test("deleting a device should store information about it in a special table") {
    val deviceT = genDeviceT.sample.get
    val deviceUuid = db.run(DeviceRepository.create(Namespaces.defaultNs, deviceT)).futureValue

    db.run(DeviceRepository.delete(Namespaces.defaultNs, deviceUuid)).futureValue

    val deletedDevices = db.run(
      DeviceRepository.deletedDevices
        .filter(_.uuid === deviceUuid)
        .result
    ).futureValue

    deletedDevices.loneElement shouldBe DeletedDevice(
      Namespaces.defaultNs,
      deviceUuid,
      deviceT.deviceId)
  }
}
