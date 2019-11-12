package com.advancedtelematic.ota.api_translator.http

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{Directive1, Route}
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.http.{DefaultRejectionHandler, ErrorHandler}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import scala.concurrent.ExecutionContext
import slick.jdbc.MySQLProfile.api._

class ApiTranslatorRoutes(namespaceExtractor: Directive1[AuthedNamespaceScope],
                          deviceNamespaceAuthorizer: Directive1[DeviceId])
                         (implicit db: Database, system: ActorSystem, mat: ActorMaterializer, exec: ExecutionContext) {

  import akka.http.scaladsl.server.Directives._

  val route: Route = {
    pathPrefix("api-translator" / "api" / "v1") {
      handleRejections(DefaultRejectionHandler.rejectionHandler) {
        ErrorHandler.handleErrors {
          new DeviceInfoResource(namespaceExtractor, deviceNamespaceAuthorizer).route
        }
      }
    }
  }
}