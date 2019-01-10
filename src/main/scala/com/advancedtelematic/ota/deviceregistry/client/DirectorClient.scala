package com.advancedtelematic.ota.deviceregistry.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.Materializer
import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs.{refinedDecoder, refinedEncoder}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.Errors.RemoteServiceError
import com.advancedtelematic.libats.http.HttpOps.HttpRequestOps
import com.advancedtelematic.libats.http.ServiceHttpClient
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TufKey}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Decoder
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

final case class EcuInfo(id: EcuIdentifier, hardwareId: HardwareIdentifier, primary: Boolean)

trait DirectorClient {
  def getEcuInfo(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfo]]
  def getPublicKey(ns: Namespace, deviceId: DeviceId, ecuIds: EcuIdentifier): Future[TufKey]
}

class DirectorHttpClient(uri: Uri, httpClient: HttpRequest => Future[HttpResponse])
                       (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) extends ServiceHttpClient(httpClient) with DirectorClient {

  private val _log = LoggerFactory.getLogger(this.getClass)
  private val pathPrefix = uri.path / "api" / "v1" / "admin" / "devices"
  implicit val ecuDecoder: Decoder[EcuInfo] = io.circe.generic.semiauto.deriveDecoder

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
}
