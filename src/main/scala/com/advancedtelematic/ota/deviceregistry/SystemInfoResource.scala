/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.server.Directives._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.ota.deviceregistry.common.Errors.MissingSystemInfo
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository
import io.circe.Json
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class SystemInfoResource(
    authNamespace: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[Uuid]
)(implicit db: Database, actorSystem: ActorSystem, ec: ExecutionContext) {
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  val logger = LoggerFactory.getLogger(this.getClass)

  def fetchSystemInfo(uuid: Uuid): Route = {
    val comp = db.run(SystemInfoRepository.findByUuid(uuid)).recover {
      case MissingSystemInfo => Json.obj()
    }
    complete(comp)
  }

  def createSystemInfo(uuid: Uuid, data: Json): Route = {
    val f = db.run(SystemInfoRepository.create(uuid, data))
    complete(Created -> f)
  }

  def updateSystemInfo(uuid: Uuid, data: Json): Route =
    complete(db.run(SystemInfoRepository.update(uuid, data)))

  def api: Route =
    (pathPrefix("devices") & authNamespace) { ns =>
      val scope = Scopes.devices(ns)
      deviceNamespaceAuthorizer { uuid =>
        (scope.get & path("system_info")) {
          fetchSystemInfo(uuid)
        } ~
        (scope.post & path("system_info")) {
          entity(as[Json]) { body =>
            createSystemInfo(uuid, body)
          }
        } ~
        (scope.put & path("system_info")) {
          entity(as[Json]) { body =>
            updateSystemInfo(uuid, body)
          }
        }
      }
    }

  def mydeviceRoutes: Route = authNamespace { authedNs => // don't use this as a namespace
    (pathPrefix("mydevice") & UuidDirectives.extractUuid) { uuid =>
      (put & path("system_info") & authedNs.oauthScope(s"ota-core.{uuid.show}.write")) {
        entity(as[Json]) { body =>
          updateSystemInfo(uuid, body)
        }
      }
    }
  }

  def route: Route = api ~ mydeviceRoutes
}
