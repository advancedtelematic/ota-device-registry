package com.advancedtelematic.ota.deviceregistry
import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.DataType.HashMethod
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.messaging_datatype.DataType.{UpdateId, ValidEcuSerial}
import com.advancedtelematic.libtuf.data.TufDataType.OperationResult
import com.advancedtelematic.libtuf_server.data.Messages.{DeviceUpdateReport, deviceUpdateReportMessageLike}
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceUpdateReportListener
import com.advancedtelematic.ota.deviceregistry.data.Codecs.failedStatDecoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CorrelationId, UpdateStat}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

class UpdateReportSpec extends ResourcePropSpec with ScalaFutures with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(50, Millis))

  private def genOperationResult(resultCode: Int): Gen[OperationResult] = for {
    fileName <- Gen.alphaNumStr.retryUntil(_.length < 254)
    checksum = "abcd1234" * 8
    length <- Gen.posNum[Int]
    resultText <- Gen.alphaStr
  } yield OperationResult(Refined.unsafeApply(fileName), Map(HashMethod.SHA256 -> Refined.unsafeApply(checksum)), length, resultCode, resultText)

  private def genDeviceUpdateReport(updateId: UpdateId, resultCode: Int): Gen[DeviceUpdateReport] = for {
    deviceId <- genDeviceUUID
    time <- Gen.posNum[Int]
    ecuSerial <- Gen.listOfN(64, Gen.alphaNumChar).map(_.mkString("")).map(Refined.unsafeApply[String, ValidEcuSerial])
    operationResult <- genOperationResult(resultCode)
  } yield DeviceUpdateReport(defaultNs, deviceId, updateId, time, Map(ecuSerial -> operationResult), resultCode)

  new DeviceUpdateReportListener(system.settings.config, db, MetricsSupport.metricRegistry).start()

  property("should save device reports and retrieve failed stats per devices") {
    val updateId = UpdateId.generate()
    val resultCodes = Seq(0, 1, 2, 2, 3, 3, 3)
    val deviceReports = resultCodes.map(genDeviceUpdateReport(updateId, _)).map(_.sample.get)

    deviceReports.foreach(messageBus.publish(_))

    eventually {
      getStats(CorrelationId.from(updateId), "device") ~> route ~> check {
        status shouldBe OK
        responseAs[Seq[UpdateStat]] shouldBe Seq(UpdateStat(0, 1), UpdateStat(1, 1), UpdateStat(2, 2), UpdateStat(3, 3))
      }
    }
  }

  property("should save device reports and retrieve failed stats per ECUs") {
    val updateId = UpdateId.generate()
    val resultCodes = Seq(1, 2, 2, 3, 3, 3, 4, 4, 4, 4)
    val deviceReports = resultCodes.map(genDeviceUpdateReport(updateId, _)).map(_.sample.get)

    deviceReports.foreach(messageBus.publish(_))

    eventually {
      getStats(CorrelationId.from(updateId), "ecu") ~> route ~> check {
        status shouldBe OK
        responseAs[Seq[UpdateStat]] shouldBe Seq(UpdateStat(1, 1), UpdateStat(2, 2), UpdateStat(3, 3), UpdateStat(4, 4))
      }
    }
  }

}
