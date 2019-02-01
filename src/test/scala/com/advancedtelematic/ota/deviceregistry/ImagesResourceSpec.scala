package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import com.advancedtelematic.ota.deviceregistry.data.Codecs.ecuImageDecoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CurrentSoftwareImage, EcuImage}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.db.CurrentImageRepository
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}

class ImagesResourceSpec extends ResourcePropSpec with ScalaFutures with Eventually {

  private val imagesApi = "images"

  private def getInstallationCount(filepaths: Seq[TargetFilename]) =
    Get(Resource.uri(imagesApi, "installed_count").withQuery(Query(filepaths.map("filepath" -> _.value): _*)))

  private def getAffected(filepath: TargetFilename) =
    Get(Resource.uri(imagesApi, "affected").withQuery(Query("filepath" -> filepath.value)))

  private def persistCurrentImages(cis: Seq[CurrentSoftwareImage]) =
    cis.map(i => db.run(CurrentImageRepository.saveSoftwareImage(i.deviceId, i.ecuId, i.image)))

  property("should get the images of a device") {
    val N = 4
    val createDevice = genCreateDeviceWithNEcus(N).generate
    val images = Gen.listOfN(N, genSoftwareImage).generate
    val deviceId = createDeviceOk(createDevice)

    val ecusWithImages = createDevice.ecus.toList.zip(images)
    val currentImages = ecusWithImages.map { case (e, i) => CurrentSoftwareImage(deviceId, e.ecuId, i) }
    persistCurrentImages(currentImages)

    getImages(deviceId) ~> route ~> check {
      status shouldBe OK
      val expected = ecusWithImages.map { case (e, i) => EcuImage(e.ecuId, e.ecuType, e.ecuId == createDevice.primaryEcu, i) }
      responseAs[Seq[EcuImage]] should contain allElementsOf expected
    }
  }

  property("should count the installed images") {
    val filepath1 = genFilePath.generate
    val filepath2 = genFilePath.generate
    val filepaths = filepath1 :: filepath2 :: filepath2 :: Nil

    val N = 3
    val createDevice = genCreateDeviceWithNEcus(N).generate
    val images = Gen.listOfN(N, genSoftwareImage).generate.zip(filepaths).map { case (i, f) => i.copy(filepath = f) }
    val deviceId = createDeviceOk(createDevice)

    val currentImages = createDevice.ecus.toList.zip(images)
      .map { case (e, i) => CurrentSoftwareImage(deviceId, e.ecuId, i) }
    currentImages.map(i => db.run(CurrentImageRepository.saveSoftwareImage(i.deviceId, i.ecuId, i.image)))

    getInstallationCount(filepaths) ~> route ~> check {
      status shouldBe OK
      responseAs[Map[TargetFilename, Int]] shouldBe Map(filepath1 -> 1, filepath2 -> 2)
    }
  }

  property("should find affected") {
    val createDevice1 = genCreateDeviceWithNEcus(1).generate
    val createDevice2 = genCreateDeviceWithNEcus(1).generate
    val ecuIds = Seq(createDevice1, createDevice2).map(_.ecus.head.ecuId)

    val deviceId1 = createDeviceOk(createDevice1)
    val deviceId2 = createDeviceOk(createDevice2)
    val devices = Seq(deviceId1, deviceId2)

    val images = Gen.listOfN(2, genSoftwareImage).generate
    val currentImages = devices.zip(ecuIds).zip(images).map { case ((d, e) , i) => CurrentSoftwareImage(d, e, i)}
    val filepath1 = currentImages.head.image.filepath
    persistCurrentImages(currentImages)

    getAffected(filepath1) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceId]].values should contain only deviceId1
    }
  }

}
