package com.advancedtelematic.ota.api_translator.http

import akka.http.scaladsl.server.{Directive1, Route}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class DeviceInfoResource(namespaceExtractor: Directive1[AuthedNamespaceScope],
                         deviceNamespaceAuthorizer: Directive1[DeviceId])
                        (implicit ec: ExecutionContext, db: Database) {
  import PaginationResult._
  import akka.http.scaladsl.server.Directives._

  val apiTranslation = new ApiTranslation()

  val route: Route = namespaceExtractor { ns =>
    pathPrefix("devices") {
      (get & pathEnd & parameters('offset.as[Long].?, 'limit.as[Long].?))  { (offset, limit) =>
        val f= apiTranslation.allDevices(ns.namespace, offset, limit)
        complete(f)
      } ~
      deviceNamespaceAuthorizer { deviceId =>
        (get & pathEnd) {
          val f = apiTranslation.findDevice(deviceId)
          complete(f)
        }
      }
    }
  }
}
