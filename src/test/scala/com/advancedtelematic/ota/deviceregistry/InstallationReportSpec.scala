package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.StatusCodes._
import akka.util.ByteString
import cats.syntax.show._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceUpdateCompletedDecoder
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCompleted
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceUpdateEventListener
import com.advancedtelematic.ota.deviceregistry.data.Codecs.installationStatDecoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{InstallationStat, InstallationStatsLevel}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.data.InstallationReportGenerators
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

class InstallationReportSpec extends ResourcePropSpec with ScalaFutures with Eventually with InstallationReportGenerators {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(50, Millis))

  implicit val msgPub = MessageBusPublisher.ignore

  val listener = new DeviceUpdateEventListener(msgPub)

  property("should save device reports and retrieve failed stats per devices") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3")
    val deviceReports = resultCodes.map(genDeviceInstallationReport(correlationId, _)).map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Device) ~> route ~> check {
        status shouldBe OK
        val expected =
          Seq(InstallationStat("0", 1, true), InstallationStat("1", 1, false), InstallationStat("2", 2, false), InstallationStat("3", 3, false))
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  property("should save device reports and retrieve failed stats per ECUs") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3")
    val deviceReports = resultCodes.map(genDeviceInstallationReport(correlationId, _)).map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Ecu) ~> route ~> check {
        status shouldBe OK
        val expected =
          Seq(InstallationStat("0", 1, true), InstallationStat("1", 1, false), InstallationStat("2", 2, false), InstallationStat("3", 3, false))
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  property("should save the whole message as a blob and get back the history for a device") {
    val deviceId       = createDeviceOk(genCreateDevice.generate)
    val correlationIds = Gen.listOfN(50, genCorrelationId).generate
    val deviceReports  = correlationIds.map(cid => genDeviceInstallationReport(cid, "0", deviceId)).map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      getReportBlob(deviceId) ~> route ~> check {
        status shouldBe OK
        responseAs[PaginationResult[DeviceUpdateCompleted]].values should contain allElementsOf deviceReports
      }
    }
  }

  def assertCsvResponse(expectedRows: Seq[String]): Assertion = {
    status shouldBe OK
    contentType shouldBe ContentTypes.`text/csv(UTF-8)`
    val result = entityAs[ByteString].utf8String.split("\n")
    result.tail should contain allElementsOf expectedRows
  }

  property("should get the device failures as a CSV") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3")
    val resultDescriptions = Map("0" -> "Description-0", "1" -> "Description-1", "2" -> "Description-2", "3" -> "Description-3")

    val createDevices = genConflictFreeCreateDevices(resultCodes.length).generate
    val deviceUuids = createDevices.map(createDeviceOk)
    val rows = deviceUuids.zip(createDevices).zip(resultCodes).map {
      case ((uuid, cd), rc) => (uuid, cd.deviceId, rc, resultDescriptions(rc))
    }
    val deviceReports = rows.map {
      case (uuid, _, rc, rd) => genDeviceInstallationReport(correlationId, rc, uuid, Some(rd))
    }.map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      val expected = rows.filter(_._3 != "0").map { case (_, cd, rc, rd) => cd.show + ";" + rc + ";" + rd }
      getFailedExport(correlationId, None) ~> route ~> check {
        assertCsvResponse(expected)
      }
    }
  }

  property("should get only the device failures for a given failure code as a CSV") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2")
    val resultDescriptions = Map("0" -> "Description-0", "1" -> "Description-1", "2" -> "Description-2")

    val createDevices = genConflictFreeCreateDevices(resultCodes.length).generate
    val deviceUuids = createDevices.map(createDeviceOk)
    val rows = deviceUuids.zip(createDevices).zip(resultCodes).map {
      case ((uuid, cd), rc) => (uuid, cd.deviceId, rc, resultDescriptions(rc))
    }
    val deviceReports = rows.map {
      case (uuid, _, rc, rd) => genDeviceInstallationReport(correlationId, rc, uuid, Some(rd))
    }.map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      val expected = rows.filter(_._3 == "2").map { case (_, cd, rc, rd) => cd.show + ";" + rc + ";" + rd }
      getFailedExport(correlationId, Some("2")) ~> route ~> check {
        assertCsvResponse(expected)
      }
    }
  }

}
