package com.advancedtelematic.ota.deviceregistry

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.DataType.ResultCode
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging.test.MockMessageBus
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.{deviceUpdateCompletedCodec, ecuReplacedCodec}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeleteDeviceRequest, DeviceUpdateCompleted, EcuReplaced}
import com.advancedtelematic.ota.deviceregistry.daemon.{DeleteDeviceListener, DeviceUpdateEventListener, EcuReplacedListener}
import com.advancedtelematic.ota.deviceregistry.data.Codecs.installationStatDecoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{InstallationStat, InstallationStatsLevel}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.data.InstallationReportGenerators
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import org.scalacheck.Gen
import org.scalatest.EitherValues._
import org.scalatest.LoneElement._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

class InstallationReportSpec extends ResourcePropSpec with ScalaFutures with Eventually with InstallationReportGenerators {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(50, Millis))

  implicit val msgPub = new MockMessageBus

  val updateListener = new DeviceUpdateEventListener(msgPub)
  val ecuReplacementListener = new EcuReplacedListener
  val deleteDeviceListener = new DeleteDeviceListener()

  property("should save device reports and retrieve failed stats per devices") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3").map(ResultCode)
    val updatesCompleted = resultCodes.map(genDeviceUpdateCompleted(correlationId, _)).map(_.generate)

    updatesCompleted.foreach(updateListener.apply)

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
    val updatesCompleted = resultCodes.map(genDeviceUpdateCompleted(correlationId, _)).map(_.generate)

    updatesCompleted.foreach(updateListener.apply)

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
    val updatesCompleted  = correlationIds.map(cid => genDeviceUpdateCompleted(cid, ResultCode("0"), deviceId)).map(_.generate)

    updatesCompleted.foreach(updateListener.apply)

    eventually {
      getReportBlob(deviceId) ~> route ~> check {
        status shouldBe OK
        responseAs[PaginationResult[DeviceUpdateCompleted]].values should contain allElementsOf updatesCompleted
      }
    }
  }

  property("does not overwrite existing reports") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val correlationId = genCorrelationId.generate
    val updateCompleted1 = genDeviceUpdateCompleted(correlationId, ResultCode("0"), deviceId).generate
    val updateCompleted2 =  genDeviceUpdateCompleted(correlationId, ResultCode("1"), deviceId).generate

    updateListener.apply(updateCompleted1).futureValue

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceUpdateCompleted]].values.loneElement.result.code shouldBe ResultCode("0")
    }

    updateListener.apply(updateCompleted2).futureValue

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceUpdateCompleted]].values.loneElement.result.code shouldBe ResultCode("0")
    }
  }

  property("should fetch installation events and ECU replacement events") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)

    val correlationId1 = genCorrelationId.generate
    val correlationId2 = genCorrelationId.generate
    val updateCompleted1 = genDeviceUpdateCompleted(correlationId1, ResultCode("0"), deviceId, receivedAt = now.plusSeconds(10)).generate
    val updateCompleted2 =  genDeviceUpdateCompleted(correlationId2, ResultCode("1"), deviceId, receivedAt = now.plusSeconds(30)).generate
    val ecuReplaced = genEcuReplaced(deviceId, now.plusSeconds(20)).generate

    updateListener(updateCompleted1).futureValue
    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceUpdateCompleted]].values.loneElement.result.code shouldBe ResultCode("0")
    }

    ecuReplacementListener(ecuReplaced).futureValue
    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]].values
      result.head.as[DeviceUpdateCompleted].right.value.result.code shouldBe ResultCode("0")
      result.tail.head.as[EcuReplaced].right.value shouldBe ecuReplaced
    }

    updateListener.apply(updateCompleted2).futureValue
    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]].values
      result.head.as[DeviceUpdateCompleted].right.value.result.code shouldBe ResultCode("0")
      result.tail.head.as[EcuReplaced].right.value shouldBe ecuReplaced
      result.tail.tail.head.as[DeviceUpdateCompleted].right.value.result.code shouldBe ResultCode("1")
    }
  }

  property("can delete replaced devices") {
    getReportBlob(genDeviceUUID.generate) ~> route ~> check {
      status shouldBe NotFound
    }

    val deviceId = createDeviceOk(genDeviceT.generate)

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]]
      result.total shouldBe 0
    }

    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)
    val ecuReplaced = genEcuReplaced(deviceId, now).generate
    ecuReplacementListener(ecuReplaced).futureValue

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]].values
      result.head.as[EcuReplaced].right.value shouldBe ecuReplaced
    }

    val deleteDeviceRequest = DeleteDeviceRequest(defaultNs, deviceId)
    deleteDeviceListener(deleteDeviceRequest).futureValue

    getReportBlob(deviceId) ~> route ~> check {
      status shouldBe NotFound
    }
  }
}
