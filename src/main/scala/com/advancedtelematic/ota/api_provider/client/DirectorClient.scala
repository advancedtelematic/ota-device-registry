package com.advancedtelematic.ota.api_provider.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.libats.data.DataType.{CorrelationId, HashMethod, Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.tracing.Tracing.ServerRequestTracing
import com.advancedtelematic.libats.http.tracing.TracingHttpClient
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetFilename}
import com.advancedtelematic.ota.api_provider.client.DirectorClient.{DirectorQueueItem, EcuInfoResponse}
import eu.timepit.refined.api.Refined
import io.circe.{Codec, Json, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto._
import cats.implicits._
import com.advancedtelematic.libats.codecs.CirceValidatedGeneric
import com.advancedtelematic.libtuf.data.ClientDataType.ClientHashes
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}


object DirectorClient {
  // TODO: This Comes from director, should be abstract somewhere. director-lite ?
  final case class Hashes(sha256: Refined[String, ValidChecksum]) {
    def toClient: ClientHashes = Map(HashMethod.SHA256 -> sha256)
  }
  final case class EcuInfoImage(filepath: TargetFilename, size: Long, hash: Hashes)
  final case class EcuInfoResponse(id: EcuIdentifier, hardwareId: HardwareIdentifier, primary: Boolean, image: EcuInfoImage)

  import com.advancedtelematic.libtuf.data.ClientCodecs._
  import com.advancedtelematic.libtuf.data.TufCodecs._
  import com.advancedtelematic.libats.codecs.CirceRefined._
  import CorrelationId._
  import EcuIdentifier._

  final case class DirectorQueueItem(correlationId: Option[CorrelationId], targets: Map[EcuIdentifier, EcuInfoImage])

  implicit val queueResponseDecoder = io.circe.Decoder.instance { cursor =>
    for {
      correlationId <- cursor.downField("correlationId").as[Option[CorrelationId]]
      imagesJson <- cursor.downField("targets").as[Map[EcuIdentifier, Json]]
      images <- imagesJson.map { case (ecuId, json) =>
        val image = json.hcursor.downField("image")

        for {
          filepath <- image.downField("filepath").as[TargetFilename]
          hashes <- image.downField("fileinfo").downField("hashes").as[Hashes]
          length <- image.downField("fileinfo").downField("length").as[Long]
        } yield ecuId -> EcuInfoImage(filepath, length, hashes)

      }.toList.sequence.map(_.toMap)
    } yield DirectorQueueItem(correlationId, images)
  }

  implicit val ecuIdentifierKeyEncoder: KeyEncoder[EcuIdentifier] = CirceValidatedGeneric.validatedGenericKeyEncoder
  implicit val ecuIdentifierKeyDecoder: KeyDecoder[EcuIdentifier] = CirceValidatedGeneric.validatedGenericKeyDecoder

  implicit val hashesCodec: Codec[Hashes] = deriveCodec
  implicit val ecuInfoImageCodec: Codec[EcuInfoImage] = deriveCodec
  implicit val ecuInfoResponseCodec: Codec[EcuInfoResponse] = deriveCodec
}

trait DirectorClient {
  def fetchDeviceEcus(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfoResponse]]

  def fetchDeviceQueue(ns: Namespace, deviceId: DeviceId): Future[Seq[DirectorQueueItem]]
}

class DirectorHttpClient(directorUri: Uri,
                         httpClient: HttpRequest => Future[HttpResponse])
                        (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer, tracing: ServerRequestTracing)
  extends TracingHttpClient(httpClient, "director") with DirectorClient {

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  import DirectorClient._

  private val _log = LoggerFactory.getLogger(this.getClass)

  private def apiUri(path: Path) = directorUri.withPath(Path("/api") / "v1" ++ Slash(path))

  override def fetchDeviceEcus(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfoResponse]] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"admin/devices/${deviceId.uuid.toString}"))).withHeaders(RawHeader("x-ats-namespace", ns.get))
    execHttp[Seq[EcuInfoResponse]](req) {
      case e if e.status == StatusCodes.NotFound =>
        _log.warn(s"Device $deviceId not found in director (http/404)")
        FastFuture.successful(Seq.empty)
    }
  }

  override def fetchDeviceQueue(ns: Namespace, deviceId: DeviceId): Future[Seq[DirectorQueueItem]] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"assignments/${deviceId.uuid.toString}"))).withHeaders(RawHeader("x-ats-namespace", ns.get))
    execHttp[Seq[DirectorQueueItem]](req) {
      case e if e.status == StatusCodes.NotFound =>
        _log.warn(s"Device assignments for $deviceId not found in director (http/404)")
        FastFuture.successful(Seq.empty)
    }
  }
}
