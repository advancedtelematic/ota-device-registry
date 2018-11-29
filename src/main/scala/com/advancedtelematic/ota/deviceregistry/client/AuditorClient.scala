package com.advancedtelematic.ota.deviceregistry.client

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.Materializer
import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs.{dateTimeDecoder, namespaceDecoder}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.http.HttpOps.HttpRequestOps
import com.advancedtelematic.libats.http.ServiceHttpClient
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, OperationResult, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Decoder

import scala.concurrent.{ExecutionContext, Future}

final case class DeviceUpdateReport(namespace: Namespace,
                                    device: DeviceId,
                                    updateId: UpdateId,
                                    timestampVersion: Int,
                                    operationResult: Map[EcuSerial, OperationResult],
                                    resultCode: Int,
                                    receivedAt: Instant)

trait AuditorClient {
  def getUpdateReports(ns: Namespace, deviceId: DeviceId): Future[Seq[DeviceUpdateReport]]
}

class AuditorHttpClient(uri: Uri, httpClient: HttpRequest => Future[HttpResponse])
                       (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) extends ServiceHttpClient(httpClient) with AuditorClient {

  implicit val deviceUpdateReportDecoder: Decoder[DeviceUpdateReport] = io.circe.generic.semiauto.deriveDecoder

  def getUpdateReports(ns: Namespace, deviceId: DeviceId): Future[Seq[DeviceUpdateReport]] = {
    val path = uri.path / "api" / "v1" / "auditor" / "update_reports" / deviceId.show
    val req = HttpRequest(HttpMethods.GET, uri.withPath(path)).withNs(ns)
    // Granted that this is unsafe if there are more than 50 reports and we could implement some pagination logic, but being
    // realistic the default values should more than enough. I haven't found a device with more than one update report.
    execHttp[PaginationResult[DeviceUpdateReport]](req)().map(_.values)
  }

}
