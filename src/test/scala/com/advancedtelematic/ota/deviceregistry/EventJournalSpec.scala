/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceEventListener
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.DataType.EventField._
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType._
import com.advancedtelematic.ota.deviceregistry.data.EventGenerators
import com.advancedtelematic.ota.deviceregistry.data.EventGenerators.EventPayload
import io.circe.testing.ArbitraryInstances
import org.scalacheck.Shrink
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.SpanSugar._

class EventJournalSpec extends ResourcePropSpec with ScalaFutures with Eventually with ArbitraryInstances with EventGenerators {
  import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  import io.circe.syntax._

  new DeviceEventListener(system.settings.config, db, MetricsSupport.metricRegistry).start()

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  property("events can be recorded in journal and retrieved") {
    forAll { (device: DeviceT, events: List[EventPayload]) =>
      val deviceUuid = createDeviceOk(device)

      recordEvents(deviceUuid, events.asJson) ~> route ~> check {
        status shouldBe NoContent
      }

      eventually(timeout(5.seconds), interval(100.millis)) {
        getEvents(deviceUuid) ~> route ~> check {
          status shouldBe OK
          responseAs[List[EventPayload]] should contain allElementsOf events
        }
      }
    }
  }

  property("indexes and fetches an event by correlationId for any device") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val eventPayloads = Seq(InstallationComplete, InstallationReport, EcuInstallationReport).map(genEventPayload).map(_.generate)

    recordEvents(deviceUuid, eventPayloads.map(_._1).asJson) ~> route ~> check {
      status shouldBe NoContent
    }

    eventPayloads.foreach {
      case (event, m) =>
        eventually(timeout(3.seconds), interval(100.millis)) {
          getEvents(CORRELATION_ID -> m(CORRELATION_ID)) ~> route ~> check {
            status shouldBe OK
            responseAs[List[EventPayload]].map(_.id) should contain only event.id
          }
        }
    }
  }

  property("indexes and fetches an event by resultCode for any device") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val eventPayloads = Seq(InstallationReport, EcuInstallationReport).map(genEventPayload).map(_.generate)

    recordEvents(deviceUuid, eventPayloads.map(_._1).asJson) ~> route ~> check {
      status shouldBe NoContent
    }

    eventPayloads.foreach {
      case (event, m) =>
        eventually(timeout(3.seconds), interval(100.millis)) {
          getEvents(RESULT_CODE -> m(RESULT_CODE)) ~> route ~> check {
            status shouldBe OK
            responseAs[List[EventPayload]].map(_.id) should contain only event.id
          }
        }
    }
  }

  property("indexes and fetches an event by correlationId for a given device") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val eventPayloads = Seq(InstallationComplete, InstallationReport, EcuInstallationReport).map(genEventPayload).map(_.generate)

    recordEvents(deviceUuid, eventPayloads.map(_._1).asJson) ~> route ~> check {
      status shouldBe NoContent
    }

    eventPayloads.foreach { case (event, m) =>
        eventually(timeout(3.seconds), interval(100.millis)) {
          getEvents(deviceUuid, CORRELATION_ID -> m(CORRELATION_ID)) ~> route ~> check {
            status shouldBe OK
            responseAs[List[EventPayload]].map(_.id) should contain only event.id
          }
        }
    }
  }

  property("indexes and fetches an event by ecuSerial for any device") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val (event, m) = genEventPayload(EcuInstallationReport).generate

    recordEvents(deviceUuid, Seq(event).asJson) ~> route ~> check {
      status shouldBe NoContent
    }

    eventually(timeout(3.seconds), interval(100.millis)) {
      getEvents(ECU_SERIAL -> m(ECU_SERIAL)) ~> route ~> check {
        status shouldBe OK
        responseAs[List[EventPayload]].map(_.id) should contain only event.id
      }
    }
  }

  property("indexes and fetches an event by resultCode for a given device") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val eventPayloads = Seq(InstallationReport, EcuInstallationReport).map(genEventPayload).map(_.generate)

    recordEvents(deviceUuid, eventPayloads.map(_._1).asJson) ~> route ~> check {
      status shouldBe NoContent
    }

    eventPayloads.foreach { case (event, m) =>
      eventually(timeout(3.seconds), interval(100.millis)) {
        getEvents(deviceUuid, RESULT_CODE -> m(RESULT_CODE)) ~> route ~> check {
          status shouldBe OK
          responseAs[List[EventPayload]].map(_.id) should contain only event.id
        }
      }
    }
  }

  property("indexes and fetches an event by ecuSerial for a given device") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val (event, m) = genEventPayload(EcuInstallationReport).generate

    recordEvents(deviceUuid, Seq(event).asJson) ~> route ~> check {
      status shouldBe NoContent
    }

    eventually(timeout(3.seconds), interval(100.millis)) {
      getEvents(deviceUuid, ECU_SERIAL -> m(ECU_SERIAL)) ~> route ~> check {
        status shouldBe OK
        responseAs[List[EventPayload]].map(_.id) should contain only event.id
      }
    }
  }
}
