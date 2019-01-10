package com.advancedtelematic.ota.deviceregistry.data
import java.time.Instant

import com.advancedtelematic.libats.data.DataType.{CampaignId, CorrelationId, MultiTargetUpdateId, Namespace}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.data.EcuIdentifier.validatedEcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuInstallationReport, InstallationResult}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import org.scalacheck.Gen

import scala.util.{Success, Try}

trait InstallationReportGenerators extends DeviceGenerators {

  val genCorrelationId: Gen[CorrelationId] =
    Gen.uuid.flatMap(uuid => Gen.oneOf(CampaignId(uuid), MultiTargetUpdateId(uuid)))

  private def genInstallationResult(resultCode: String): Gen[InstallationResult] =
    Try(resultCode.toInt == 0)
      .orElse(Success(false))
      .map(b => Gen.alphaStr.flatMap(InstallationResult(b, resultCode, _)))
      .get

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

  def genDeviceInstallationReport(correlationId: CorrelationId, resultCode: String, deviceId: DeviceId = genDeviceUUID.sample.get): Gen[DeviceInstallationReport] =
    for {
      result     <- genInstallationResult(resultCode)
      ecuReports <- genEcuReports(correlationId, resultCode)
      receivedAt = Instant.ofEpochMilli(0)
      namespace = Namespace("default")
    } yield DeviceInstallationReport(namespace, deviceId, correlationId, result, ecuReports, report = None, receivedAt)

}
