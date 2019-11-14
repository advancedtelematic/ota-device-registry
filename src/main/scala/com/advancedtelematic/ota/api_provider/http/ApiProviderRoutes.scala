package com.advancedtelematic.ota.api_provider.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.{Directive1, Directives, Route}
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.http.{DefaultRejectionHandler, ErrorHandler}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext


class ApiProviderRoutes(namespaceExtractor: Directive1[AuthedNamespaceScope],
                        deviceNamespaceAuthorizer: Directive1[DeviceId],
                        directorClient: DirectorClient)
                       (implicit db: Database, system: ActorSystem, mat: ActorMaterializer, exec: ExecutionContext) {

  import akka.http.scaladsl.server.Directives._

  val withVersionHeaders = {
    val header = RawHeader("x-here-ota-api-provider-version", s"api-provider@device-registry")
    Directives.respondWithHeader(header)
  }

  val route: Route = withVersionHeaders {
    pathPrefix("api-provider") {
      pathPrefix ("api" / "v1alpha") {
        handleRejections(DefaultRejectionHandler.rejectionHandler) {
          ErrorHandler.handleErrors {
            new DeviceInfoResource(namespaceExtractor, deviceNamespaceAuthorizer, directorClient).route
          }
        }
      } ~
        new ApiDocsResource().route
    }
  }
}