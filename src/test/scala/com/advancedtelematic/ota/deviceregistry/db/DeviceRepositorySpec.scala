/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant
import java.util.UUID

import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.DeviceGenerators.{genDeviceId, genDeviceT}
import com.advancedtelematic.ota.deviceregistry.data.Uuid.Valid
import com.advancedtelematic.ota.deviceregistry.data.{Namespaces, Uuid}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FunSuite, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class DeviceRepositorySpec extends FunSuite with DatabaseSpec with ScalaFutures with Matchers {

  test("updateLastSeen sets activated_at the first time only") {

    val device = genDeviceT.sample.get.copy(deviceId = Some(genDeviceId.sample.get))
    val setTwice = for {
      uuid   <- DeviceRepository.create(Namespaces.defaultNs, device)
      first  <- DeviceRepository.updateLastSeen(uuid, Instant.now()).map(_._1)
      second <- DeviceRepository.updateLastSeen(uuid, Instant.now()).map(_._1)
    } yield (first, second)

    whenReady(db.run(setTwice), Timeout(Span(10, Seconds))) {
      case (f, s) =>
        f shouldBe (true)
        s shouldBe (false)
    }
  }

  test("activated_at can be counted") {

    val device = genDeviceT.sample.get.copy(deviceId = Some(genDeviceId.sample.get))
    val createDevice = for {
      uuid <- DeviceRepository.create(Namespaces.defaultNs, device)
      now = Instant.now()
      _     <- DeviceRepository.updateLastSeen(uuid, now)
      count <- DeviceRepository.countActivatedDevices(Namespaces.defaultNs, now, now.plusSeconds(100))
    } yield count

    whenReady(db.run(createDevice), Timeout(Span(10, Seconds))) { count =>
      count shouldBe (1)
    }
  }

  test("updateLastSeen UUID is case insensitive") {

    val uuidStr   = UUID.randomUUID.toString
    val uuidUpper = Uuid(Refined.unsafeApply(uuidStr.toUpperCase))
    val uuidLower = Uuid(Refined.unsafeApply(uuidStr.toLowerCase))
    val device    = genDeviceT.sample.get.copy(deviceId = Some(genDeviceId.sample.get), deviceUuid = Some(uuidUpper))
    val updateLastSeen = for {
      _   <- DeviceRepository.create(Namespaces.defaultNs, device)
      res <- DeviceRepository.updateLastSeen(uuidLower, Instant.now()).map(_._1)
    } yield res

    whenReady(db.run(updateLastSeen), Timeout(Span(10, Seconds))) { r =>
      r shouldBe true
    }
  }
}
