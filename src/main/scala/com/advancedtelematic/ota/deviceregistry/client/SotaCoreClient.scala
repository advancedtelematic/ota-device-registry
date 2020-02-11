package com.advancedtelematic.ota.deviceregistry.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.Materializer
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.HttpOps.HttpRequestOps
import com.advancedtelematic.libats.http.ServiceHttpClient
import com.advancedtelematic.ota.deviceregistry.data.DataType.PackageListItem
import com.advancedtelematic.ota.deviceregistry.data.PackageId
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Decoder

import scala.concurrent.{ExecutionContext, Future}

final case class SotaCoreBlacklistedPackage(packageId: PackageId, comment: String)

trait SotaCoreClient {
  def getBlacklistedPackages(ns: Namespace): Future[Seq[PackageListItem]]
}

class SotaCoreHttpClient(uri: Uri, httpClient: HttpRequest => Future[HttpResponse])
                        (implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) extends ServiceHttpClient(httpClient) with SotaCoreClient {

  implicit val deviceUpdateReportDecoder: Decoder[SotaCoreBlacklistedPackage] = io.circe.generic.semiauto.deriveDecoder

  private def sotaPakageToPackage(ns: Namespace, sotaPackage: SotaCoreBlacklistedPackage): PackageListItem =
    PackageListItem(ns, sotaPackage.packageId, sotaPackage.comment)

  def getBlacklistedPackages(ns: Namespace): Future[Seq[PackageListItem]] = {
    val path = uri.path / "api" / "v1" / "blacklist"
    val req = HttpRequest(HttpMethods.GET, uri.withPath(path)).withNs(ns)
    execHttp[Seq[SotaCoreBlacklistedPackage]](req)().map(_.map(sotaPakageToPackage(ns, _)))
  }

}
