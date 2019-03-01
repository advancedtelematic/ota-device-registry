package com.advancedtelematic.ota.deviceregistry.data

import java.time.Instant

import com.advancedtelematic.libats.data.DataType.{CampaignId, CorrelationId, MultiTargetUpdateId, Namespace}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.data.EcuIdentifier.validatedEcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuInstallationReport, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceUpdateCompleted
import org.scalacheck.Gen

import scala.util.{Success, Try}

trait InstallationReportGenerators extends DeviceGenerators {

  val genCorrelationId: Gen[CorrelationId] =
    Gen.uuid.flatMap(uuid => Gen.oneOf(CampaignId(uuid), MultiTargetUpdateId(uuid)))

  private def genInstallationResult(resultCode: String, resultDescription: Option[String] = None): Gen[InstallationResult] = {
    val success = Try(resultCode.toInt == 0).orElse(Success(false))
    val description = resultDescription.getOrElse(Gen.alphaStr.sample.get)
    InstallationResult(success.get, resultCode, description)
  }

  private def genEcuReports(correlationId: CorrelationId,
                            resultCode: String,
                            n: Int = 1): Gen[Map[EcuIdentifier, EcuInstallationReport]] =
    Gen.listOfN(n, genEcuReportTuple(correlationId, resultCode)).map(_.toMap)

  private def genEcuReportTuple(correlationId: CorrelationId,
                                resultCode: String): Gen[(EcuIdentifier, EcuInstallationReport)] =
    for {
      ecuId  <- Gen.listOfN(64, Gen.alphaNumChar).map(_.mkString("")).map(validatedEcuIdentifier.from(_).right.get)
      report <- genEcuInstallationReport(resultCode)
    } yield ecuId -> report

  private def genEcuInstallationReport(resultCode: String): Gen[EcuInstallationReport] =
    for {
      result <- genInstallationResult(resultCode)
      target <- Gen.listOfN(1, Gen.alphaStr)
    } yield EcuInstallationReport(result, target, None)

  def genDeviceInstallationReport(correlationId: CorrelationId, resultCode: String, deviceId: DeviceId = genDeviceUUID.sample.get, resultDescription: Option[String] = None): Gen[DeviceUpdateCompleted] =
    for {
      result     <- genInstallationResult(resultCode, resultDescription)
      ecuReports <- genEcuReports(correlationId, resultCode)
      receivedAt = Instant.ofEpochMilli(0)
      namespace = Namespace("default")
    } yield DeviceUpdateCompleted(namespace, receivedAt, correlationId, deviceId, result, ecuReports, rawReport = None)

}
