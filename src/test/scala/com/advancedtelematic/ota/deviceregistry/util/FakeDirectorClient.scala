package com.advancedtelematic.ota.deviceregistry.util

import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.TufKey
import com.advancedtelematic.ota.deviceregistry.client.{DirectorClient, EcuInfo}
import com.advancedtelematic.ota.deviceregistry.data.DataType.SoftwareImage
import com.advancedtelematic.ota.deviceregistry.data.DataType.Ecu

import scala.collection.JavaConverters._
import scala.collection._
import scala.concurrent.Future

class FakeDirectorClient extends DirectorClient {

  private val ecus = new ConcurrentHashMap[(Namespace, DeviceId, EcuIdentifier), Ecu]().asScala
  private val images = new ConcurrentHashMap[(Namespace, DeviceId, EcuIdentifier), SoftwareImage]().asScala

  def addEcus(ns: Namespace, deviceId: DeviceId, newEcus: Ecu*): Unit =
    newEcus.foreach(ecu => ecus.put((ns, deviceId, ecu.ecuId), ecu))

  def addImages(ns: Namespace, deviceId: DeviceId, ecuId: EcuIdentifier, newImages: SoftwareImage*): Unit =
    newImages.foreach(image => images.put((ns, deviceId, ecuId), image))

  override def getEcuInfo(ns: Namespace, did: DeviceId): Future[Seq[EcuInfo]] = FastFuture.successful {
    ecus
      .filter { case ((_ns, _did, _), _) => _ns == ns && _did == did }
      .values
      .map(ecu => EcuInfo(ecu.ecuId, ecu.ecuType, ecu.primary))
      .toSeq
  }

  override def getPublicKey(ns: Namespace, did: DeviceId, ecuId: EcuIdentifier): Future[TufKey] = FastFuture.successful {
    ecus.getOrElse((ns, did, ecuId), throw new IllegalArgumentException(s"No ECU for ns: $ns deviceId: $did ecuId: $ecuId.")).tufKey
  }

  override def getImages(ns: Namespace, did: DeviceId): Future[Seq[(EcuIdentifier, SoftwareImage)]] = FastFuture.successful {
    images
        .filter { case ((_ns, _did, _), _) => _ns == ns && _did == did }
        .map { case ((_, _, ecuId), image) => ecuId -> image }
        .toSeq
    }

}
