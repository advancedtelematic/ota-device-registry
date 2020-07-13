package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.DataType.ResultCode
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceUpdateCompletedCodec
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCompleted
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceUpdateEventListener
import com.advancedtelematic.ota.deviceregistry.data.Codecs.installationStatDecoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{InstallationStat, InstallationStatsLevel}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.data.InstallationReportGenerators
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

class InstallationReportSpec extends ResourcePropSpec with ScalaFutures with Eventually with InstallationReportGenerators {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(50, Millis))

  implicit val msgPub = MessageBusPublisher.ignore

  val listener = new DeviceUpdateEventListener(msgPub)

  property("should save device reports and retrieve failed stats per devices") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3").map(ResultCode)
    val deviceReports = resultCodes.map(genDeviceInstallationReport(correlationId, _)).map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Device) ~> route ~> check {
        status shouldBe OK
        val expected = Seq(
            InstallationStat(ResultCode("0"), 1, true),
            InstallationStat(ResultCode("1"), 1, false),
            InstallationStat(ResultCode("2"), 2, false),
            InstallationStat(ResultCode("3"), 3, false)
        )
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  property("should save device reports and retrieve failed stats per ECUs") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3").map(ResultCode)
    val deviceReports = resultCodes.map(genDeviceInstallationReport(correlationId, _)).map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Ecu) ~> route ~> check {
        status shouldBe OK
        val expected = Seq(
          InstallationStat(ResultCode("0"), 1, true),
          InstallationStat(ResultCode("1"), 1, false),
          InstallationStat(ResultCode("2"), 2, false),
          InstallationStat(ResultCode("3"), 3, false)
        )
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  property("should save the whole message as a blob and get back the history for a device") {
    val deviceId       = createDeviceOk(genDeviceT.generate)
    val correlationIds = Gen.listOfN(50, genCorrelationId).generate
    val deviceReports  = correlationIds.map(cid => genDeviceInstallationReport(cid, ResultCode("0"), deviceId)).map(_.generate)

    deviceReports.foreach(listener.apply)

    eventually {
      getReportBlob(deviceId) ~> route ~> check {
        status shouldBe OK
        responseAs[PaginationResult[DeviceUpdateCompleted]].values should contain allElementsOf deviceReports
      }
    }
  }

  property("does not overwrite existing reports") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val correlationId = genCorrelationId.generate
    val deviceReport01 = genDeviceInstallationReport(correlationId, ResultCode("0"), deviceId).generate

    val deviceReport02 =  genDeviceInstallationReport(correlationId, ResultCode("1"), deviceId).generate

    listener.apply(deviceReport01).futureValue

    import org.scalatest.LoneElement._

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceUpdateCompleted]].values.loneElement.result.code shouldBe ResultCode("0")
    }

    listener.apply(deviceReport02).futureValue

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceUpdateCompleted]].values.loneElement.result.code shouldBe ResultCode("0")
    }
  }
}
