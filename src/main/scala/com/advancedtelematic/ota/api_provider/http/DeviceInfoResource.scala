package com.advancedtelematic.ota.api_provider.http

import akka.http.scaladsl.server.{Directive1, Route}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.http.AnyvalMarshallingSupport._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.db.EventJournal
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class DeviceInfoResource(namespaceExtractor: Directive1[AuthedNamespaceScope],
                         deviceNamespaceAuthorizer: Directive1[DeviceId],
                         directorClient: DirectorClient)
                        (implicit ec: ExecutionContext, db: Database) {
  import PaginationResult._
  import akka.http.scaladsl.server.Directives._
  import com.advancedtelematic.ota.api_provider.data.DataType._
  import com.advancedtelematic.ota.deviceregistry.DevicesResource.correlationIdUnmarshaller

  val apiProvider = new ApiProvider(directorClient, new EventJournal())

  val route: Route = namespaceExtractor { ns =>
    pathPrefix("devices") {
      (get & pathEnd & parameters(('deviceId.as[DeviceOemId].?, 'offset.as[Long].?, 'limit.as[Long].?)))  { (clientDeviceId, offset, limit) =>
        val f = apiProvider.allDevices(ns.namespace, clientDeviceId, limit, offset)
        complete(f)
      } ~
      deviceNamespaceAuthorizer { deviceId =>
        (get & pathEnd) {
          val f = apiProvider.findDevice(ns.namespace, deviceId)
          complete(f)
        } ~
        (get & path("events") & parameter('updateId.as[CorrelationId].?)) { correlationId =>
          val f = apiProvider.findUpdateEvents(ns.namespace, deviceId, correlationId)
          complete(f)
        } ~
        (get & path("queue")) {
          val f = apiProvider.deviceQueue(ns.namespace, deviceId)
          complete(f)
        }
      }
    }
  }
}
