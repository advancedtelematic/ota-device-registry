package com.advancedtelematic.ota.api_provider.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.libats.data.DataType.{Namespace, ValidChecksum}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.tracing.Tracing.ServerRequestTracing
import com.advancedtelematic.libats.http.tracing.TracingHttpClient
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, TargetFilename}
import com.advancedtelematic.ota.api_provider.client.DirectorClient.EcuInfoResponse
import eu.timepit.refined.api.Refined
import io.circe.Codec
import io.circe.generic.semiauto._

import scala.concurrent.{ExecutionContext, Future}

object DirectorClient {
  // TODO: This Comes from director, should be abstract somewhere. director-lite ?
  final case class Hashes(sha256: Refined[String, ValidChecksum])
  final case class EcuInfoImage(filepath: TargetFilename, size: Long, hash: Hashes)
  final case class EcuInfoResponse(id: EcuIdentifier, hardwareId: HardwareIdentifier, primary: Boolean, image: EcuInfoImage)

  import com.advancedtelematic.libtuf.data.ClientCodecs._
  import com.advancedtelematic.libtuf.data.TufCodecs._
  import com.advancedtelematic.libats.codecs.CirceRefined._

  implicit val hashesCodec: Codec[Hashes] = deriveCodec
  implicit val ecuInfoImageCodec: Codec[EcuInfoImage] = deriveCodec
  implicit val ecuInfoResponseCodec: Codec[EcuInfoResponse] = deriveCodec
}

trait DirectorClient {
  def fetchDeviceEcus(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfoResponse]]
}

class DirectorHttpClient(directorUri: Uri,
                         httpClient: HttpRequest => Future[HttpResponse])
                        (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer, tracing: ServerRequestTracing)
  extends TracingHttpClient(httpClient, "director") with DirectorClient {

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  import DirectorClient._

  private def apiUri(path: Path) = directorUri.withPath(Path("/api") / "v1" ++ Slash(path))

  override def fetchDeviceEcus(ns: Namespace, deviceId: DeviceId): Future[Seq[EcuInfoResponse]] = {
    val req = HttpRequest(HttpMethods.GET, uri = apiUri(Path(s"admin/devices/${deviceId.uuid.toString}"))).withHeaders(RawHeader("x-ats-namespace", ns.get))
    execHttp[Seq[EcuInfoResponse]](req) {
      case e if e.status == StatusCodes.NotFound =>
        FastFuture.successful(Seq.empty)
    }
  }
}
