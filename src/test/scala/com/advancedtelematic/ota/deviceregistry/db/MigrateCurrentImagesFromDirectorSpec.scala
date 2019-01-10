package com.advancedtelematic.ota.deviceregistry.db

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.syntax.either._
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.test.{DatabaseSpec, LongTest}
import com.advancedtelematic.libtuf.data.TufDataType.ValidTargetFilename
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CurrentSoftwareImage, Ecu, SoftwareImage}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.data.{Checksum, DeviceGenerators, Namespaces}
import com.advancedtelematic.ota.deviceregistry.util.FakeDirectorClient
import eu.timepit.refined.api.Refined
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{EitherValues, FunSuite, Matchers}

class MigrateCurrentImagesFromDirectorSpec
    extends FunSuite
    with DeviceGenerators
    with ScalaFutures
    with DatabaseSpec
    with Matchers
    with EitherValues
    with Namespaces
    with Eventually
    with LongTest {

  implicit val system: ActorSystem = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  test("decodes image information from director format") {
    val json = io.circe.parser.parse(
      """
        |[
        |    [
        |        "fe243b76ff15ef03c5c2601b4dd7903998bc3f9c3c029bb57981f3c8b8df6c9f",
        |        {
        |            "fileinfo": {
        |                "hashes": {
        |                    "sha256": "3da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885"
        |                },
        |                "length": 0
        |            },
        |            "filepath": "qemux86-64-3da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885"
        |        }
        |    ],
        |    [
        |        "0e243b76ff15ef03c5c2601b4dd7903998bc3f9c3c029bb57981f3c8b8df6c9f",
        |        {
        |            "fileinfo": {
        |                "hashes": {
        |                    "sha256": "0da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885"
        |                },
        |                "length": 0
        |            },
        |            "filepath": "qemux86-64-0da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885"
        |        }
        |    ]
        |]
      """.stripMargin
    ).valueOr(throw _)

    val expected = Seq(
      EcuIdentifier("fe243b76ff15ef03c5c2601b4dd7903998bc3f9c3c029bb57981f3c8b8df6c9f").right.get ->
        SoftwareImage(
          Refined.unsafeApply[String, ValidTargetFilename](
            "qemux86-64-3da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885"
          ),
          Checksum("3da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885").right.get,
          0
        ),
      EcuIdentifier("0e243b76ff15ef03c5c2601b4dd7903998bc3f9c3c029bb57981f3c8b8df6c9f").right.get ->
        SoftwareImage(
          Refined.unsafeApply[String, ValidTargetFilename](
            "qemux86-64-0da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885"
          ),
          Checksum("0da54f6e27a3d99193f241e423db05fd1f60558e66bdad9aa7dd707ab842c885").right.get,
          0
        )
    )

    import com.advancedtelematic.ota.deviceregistry.client.DirectorHttpClient.softwareImageDecoder
    json.as[Seq[(EcuIdentifier, SoftwareImage)]].right.value shouldBe expected
  }

  test("should store the images from director") {
    val N = 20
    val newDevice = genCreateDeviceWithNEcus(N).generate
    val deviceId  = db.run(DeviceRepository.create(defaultNs, newDevice)).futureValue

    val ecus = newDevice.ecus.map(e => Ecu(deviceId, e.ecuId, e.ecuType, primary = false, e.clientKey)).toList
    val images = Gen.listOfN(N, genSoftwareImage).generate
    val ecuWithImages = ecus.zip(images)

    val director = new FakeDirectorClient
    director.addEcus(defaultNs, deviceId, ecus: _*)
    ecuWithImages.foreach { case (ecu, image) => director.addImages(defaultNs, deviceId, ecu.ecuId, image) }

    val migrator = new MigrateInstalledImagesFromDirector(director)
    migrator.run.futureValue

    eventually {
      val result: Seq[(EcuIdentifier, SoftwareImage)] = db
        .run(CurrentImageRepository.listImagesForDevice(deviceId))
        .map(_.map(r => r.ecuId -> SoftwareImage(r.image.filepath, r.image.checksum, r.image.size)))
        .futureValue
      result should contain theSameElementsAs ecuWithImages.map { case (ecu, image) => ecu.ecuId -> image }
    }
  }

  test("the migration is idempotent") {
    val newDevice = genCreateDeviceWithNEcus(1).generate
    val deviceId  = db.run(DeviceRepository.create(defaultNs, newDevice)).futureValue
    val ecuId = newDevice.ecus.head.ecuId
    val image = genSoftwareImage.generate

    val director = new FakeDirectorClient
    val migrator = new MigrateInstalledImagesFromDirector(director)

    migrator.saveImage(deviceId, ecuId, image).futureValue
    val foundImages1 = db.run(CurrentImageRepository.listImagesForDevice(deviceId)).futureValue
    foundImages1 should contain only CurrentSoftwareImage(deviceId, ecuId, image)

    // Repeat to make sure the method is idempotent when given the same value
    migrator.saveImage(deviceId, ecuId, image).futureValue
    val foundImages2 = db.run(CurrentImageRepository.listImagesForDevice(deviceId)).futureValue
    foundImages2 should contain only CurrentSoftwareImage(deviceId, ecuId, image)

  }

}
