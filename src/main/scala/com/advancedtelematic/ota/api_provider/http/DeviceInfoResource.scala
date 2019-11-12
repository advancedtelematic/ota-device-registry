package com.advancedtelematic.ota.api_provider.http

import akka.http.scaladsl.server.{Directive1, Route}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class DeviceInfoResource(namespaceExtractor: Directive1[AuthedNamespaceScope],
                         deviceNamespaceAuthorizer: Directive1[DeviceId],
                         directorClient: DirectorClient)
                        (implicit ec: ExecutionContext, db: Database) {
  import PaginationResult._
  import akka.http.scaladsl.server.Directives._

  val apiProvider = new ApiProvider(directorClient)

  val route: Route = namespaceExtractor { ns =>
    pathPrefix("devices") {
      (get & pathEnd & parameters('offset.as[Long].?, 'limit.as[Long].?))  { (offset, limit) =>
        val f = apiProvider.allDevices(ns.namespace, offset, limit)
        complete(f)
      } ~
      deviceNamespaceAuthorizer { deviceId =>
        (get & pathEnd) {
          val f = apiProvider.findDevice(ns.namespace, deviceId)
          complete(f)
        }
      }
    }
  }
}
