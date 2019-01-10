package com.advancedtelematic.ota.deviceregistry.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.Materializer
import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs.{hashMethodKeyDecoder, refinedDecoder, refinedEncoder}
import com.advancedtelematic.libats.data.DataType.HashMethod.HashMethod
import com.advancedtelematic.libats.data.DataType.{Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.Errors.RemoteServiceError
import com.advancedtelematic.libats.http.HttpOps.HttpRequestOps
import com.advancedtelematic.libats.http.ServiceHttpClient
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetFilename, TufKey}
import com.advancedtelematic.ota.deviceregistry.data.Checksum
import com.advancedtelematic.ota.deviceregistry.data.DataType.SoftwareImage
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import eu.timepit.refined.api.Refined
import io.circe.Decoder
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

final case class EcuInfo(id: EcuIdentifier, hardwareId: HardwareIdentifier, primary: Boolean)

trait DirectorClient {
  def getEcuInfo(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfo]]
  def getPublicKey(ns: Namespace, deviceId: DeviceId, ecuIds: EcuIdentifier): Future[TufKey]
  def getImages(ns: Namespace, deviceId: DeviceId): Future[Seq[(EcuIdentifier, SoftwareImage)]]
}

class DirectorHttpClient(uri: Uri, httpClient: HttpRequest => Future[HttpResponse])
                       (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) extends ServiceHttpClient(httpClient) with DirectorClient {

  import DirectorHttpClient._

  private val _log = LoggerFactory.getLogger(this.getClass)
  private val pathPrefix = uri.path / "api" / "v1" / "admin" / "devices"


  override def getEcuInfo(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfo]] = {
    val path = pathPrefix / deviceId.show
    val req = HttpRequest(HttpMethods.GET, uri.withPath(path)).withNs(ns)
    execHttp[Seq[EcuInfo]](req)().recover {
      case e: RemoteServiceError =>
        _log.warn(s"Unable to fetch ECU info for ns $ns and device $deviceId.", e)
        Seq.empty
    }
  }

  override def getPublicKey(ns: Namespace, deviceId: DeviceId, ecuId: EcuIdentifier): Future[TufKey] = {
    val path = pathPrefix / deviceId.show / "ecus" / ecuId.value / "public_key"
    val req = HttpRequest(HttpMethods.GET, uri.withPath(path)).withNs(ns)
    execHttp[TufKey](req)()
  }

  override def getImages(ns: Namespace, deviceId: DeviceId): Future[Seq[(EcuIdentifier, SoftwareImage)]] = {
    val path = pathPrefix / deviceId.show / "images"
    val req = HttpRequest(HttpMethods.GET, uri.withPath(path)).withNs(ns)
    execHttp[Seq[(EcuIdentifier, SoftwareImage)]](req)()
  }
}

object DirectorHttpClient {
  implicit val ecuDecoder: Decoder[EcuInfo] = io.circe.generic.semiauto.deriveDecoder
  implicit val softwareImageDecoder: Decoder[SoftwareImage] = Decoder.instance { c =>
    c.downField("filepath").as[TargetFilename].flatMap { filepath =>
      c.downField("fileinfo").downField("hashes").as[Map[HashMethod, Refined[String, ValidChecksum]]].flatMap { hash =>
        c.downField("fileinfo").downField("length").as[Long].map { length =>
          SoftwareImage(filepath, Checksum(hash.head._2.value).right.get, length)
        }
      }
    }
  }
}