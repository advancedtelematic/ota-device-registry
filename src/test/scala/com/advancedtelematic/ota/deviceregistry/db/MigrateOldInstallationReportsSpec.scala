package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.syntax.either._
import com.advancedtelematic.libats.data.DataType.HashMethod.HashMethod
import com.advancedtelematic.libats.data.DataType.{HashMethod, MultiTargetUpdateId, Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.data.EcuIdentifier.validatedEcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType._
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceInstallationReportDecoder
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.client.DeviceUpdateReport
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.data.{DeviceGenerators, Namespaces}
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository.fetchInstallationHistory
import com.advancedtelematic.ota.deviceregistry.util.FakeAuditorClient
import eu.timepit.refined.api.Refined
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.SpanSugar._
import org.scalatest.{EitherValues, FunSuite, Matchers}

class MigrateOldInstallationReportsSpec
    extends FunSuite
    with DeviceGenerators
    with ScalaFutures
    with DatabaseSpec
    with Matchers
    with EitherValues
    with Namespaces
    with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.millis)
  implicit val system: ActorSystem             = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  private val genOperationResultPair: Gen[(EcuIdentifier, OperationResult)] =
    genEcuId.flatMap(ecuId => genOperationResult.map(ecuId -> _))

  private val genValidChecksumPair: Gen[(HashMethod, Refined[String, ValidChecksum])] = for {
    hashMethod <- Gen.const(HashMethod.SHA256)
    chars      <- Gen.listOfN(64, Gen.oneOf("abcdefghijklmnopqrstuvwxyz0123456789")).map(_.mkString(""))
  } yield hashMethod -> Refined.unsafeApply[String, ValidChecksum](chars)

  private def genOperationResult: Gen[OperationResult] =
    for {
      target     <- Gen.alphaStr.map(Refined.unsafeApply[String, ValidTargetFilename])
      hashes     <- genValidChecksumPair.map(Seq(_)).map(_.toMap)
      length     <- Gen.chooseNum[Long](0, 10000000000000L)
      resultCode <- Gen.chooseNum[Int](0, 10)
      resultText <- Gen.alphaStr
    } yield OperationResult(target, hashes, length, resultCode, resultText)

  private def genDeviceUpdateReport(deviceId: DeviceId): Gen[DeviceUpdateReport] =
    for {
      updateId        <- Gen.uuid.map(UpdateId.apply)
      timestamp       <- Gen.chooseNum[Int](0, 1000000000)
      operationResult <- Gen.chooseNum(1, 5).flatMap(Gen.listOfN(_, genOperationResultPair)).map(_.toMap)
      resultCode      <- Gen.chooseNum(0, 10)
      receivedAt      <- Gen.const(Instant.ofEpochMilli(0))
    } yield DeviceUpdateReport(defaultNs, deviceId, updateId, timestamp, operationResult, resultCode, receivedAt)


  test("should correctly transform a DeviceUpdateReport to a DeviceInstallationReport") {
    val oldReport = DeviceUpdateReport(
      Namespace("migration-test"),
      DeviceId(UUID.fromString("88888888-4444-4444-4444-CCCCCCCCCCCC")),
      UpdateId(UUID.fromString("99999999-5555-5555-5555-DDDDDDDDDDDD")),
      8,
      Map(
        validatedEcuIdentifier.from("1234abcd").right.get -> OperationResult(
          Refined.unsafeApply[String, ValidTargetFilename]("the-target-file"),
          Map(HashMethod.SHA256 -> Refined.unsafeApply[String, ValidChecksum]("1234ABCD" * 8)),
          27,
          0,
          "All good."
        ),
        validatedEcuIdentifier.from("3456cdef").right.get -> OperationResult(
          Refined.unsafeApply[String, ValidTargetFilename]("the-other-target-file"),
          Map(HashMethod.SHA256 -> Refined.unsafeApply[String, ValidChecksum]("3456CDEF" * 8)),
          72,
          2,
          "Something went wrong."
        )
      ),
      2,
      Instant.ofEpochMilli(123456)
    )

    val newReport = DeviceInstallationReport(
      Namespace("migration-test"),
      DeviceId(UUID.fromString("88888888-4444-4444-4444-CCCCCCCCCCCC")),
      MultiTargetUpdateId(UUID.fromString("99999999-5555-5555-5555-DDDDDDDDDDDD")),
      InstallationResult(success = false, "19", "One or more targeted ECUs failed to update"),
      Map(
        validatedEcuIdentifier.from("1234abcd").right.get -> EcuInstallationReport(
          InstallationResult(success = true, "0", "All good."),
          Seq("the-target-file"),
          None
        ),
        validatedEcuIdentifier.from("3456cdef").right.get -> EcuInstallationReport(
          InstallationResult(success = false, "2", "Something went wrong."),
          Seq("the-other-target-file"),
          None
        )
      ),
      None,
      Instant.ofEpochMilli(123456)
    )

    new MigrateOldInstallationReports(new FakeAuditorClient).toNewSchema(oldReport) shouldBe newReport
  }

  test("should store the reports in the new format and be idempotent") {
    val deviceT  = genCreateDevice.sample.get
    val deviceId = db.run(DeviceRepository.create(defaultNs, deviceT)).futureValue

    val auditor = new FakeAuditorClient
    val reports = Gen.listOfN(20, genDeviceUpdateReport(deviceId)).generate
    auditor.addDeviceUpdateReport(defaultNs, deviceId, reports: _*)

    val migrator = new MigrateOldInstallationReports(auditor)
    val expectedReports = reports.map(migrator.toNewSchema)
    migrator.run.futureValue

    eventually {
      val result = db
        .run(fetchInstallationHistory(deviceId, offset = None, limit = None))
        .map(_.values)
        .map(_.map(_.as[DeviceInstallationReport].valueOr(throw _)))
        .futureValue
      result should contain allElementsOf expectedReports
    }

    // Migration is idempotent
    migrator.run.futureValue
    eventually {
      val result = db
        .run(fetchInstallationHistory(deviceId, offset = None, limit = None))
        .map(_.values)
        .map(_.map(_.as[DeviceInstallationReport].valueOr(throw _)))
        .futureValue
      result should contain theSameElementsAs expectedReports
    }
  }

}
