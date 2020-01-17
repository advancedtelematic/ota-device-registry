package com.advancedtelematic.ota.deviceregistry
import akka.actor.ActorSystem
import akka.http.scaladsl.server.{Directive1, Directives, Route}
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.http.DefaultRejectionHandler.rejectionHandler
import com.advancedtelematic.libats.http.ErrorHandler
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import com.advancedtelematic.ota.api_provider.http.ApiProviderRoutes

import scala.concurrent.ExecutionContext
import slick.jdbc.MySQLProfile.api._

/**
  * Base API routing class.
  */
class DeviceRegistryRoutes(
    namespaceExtractor: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[DeviceId],
    messageBus: MessageBusPublisher,
    directorClient: DirectorClient
)(implicit db: Database, system: ActorSystem, mat: ActorMaterializer, exec: ExecutionContext)
    extends Directives {

  val route: Route =
    pathPrefix("api") {
      pathPrefix("v1") {
        handleRejections(rejectionHandler) {
          ErrorHandler.handleErrors {
            new DevicesResource(namespaceExtractor, messageBus, deviceNamespaceAuthorizer).route ~
            new SystemInfoResource(messageBus, namespaceExtractor, deviceNamespaceAuthorizer).route ~
            new PublicCredentialsResource(namespaceExtractor, messageBus, deviceNamespaceAuthorizer).route ~
            new GroupsResource(namespaceExtractor, deviceNamespaceAuthorizer).route
          }
        }
      } ~
      pathPrefix("v2") {
        handleRejections(rejectionHandler) {
          ErrorHandler.handleErrors {
            new DeviceResource2(namespaceExtractor, deviceNamespaceAuthorizer).route
          }
        }
      }
    } ~
    new ApiProviderRoutes(namespaceExtractor, deviceNamespaceAuthorizer, directorClient).route
}
