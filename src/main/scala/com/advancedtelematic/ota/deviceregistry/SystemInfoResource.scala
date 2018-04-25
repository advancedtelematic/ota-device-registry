/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.server.Directives._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.ota.deviceregistry.common.Errors.MissingSystemInfo
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository.NetworkInfo
import com.advancedtelematic.ota.deviceregistry.messages.DeviceSystemInfoChanged
import io.circe.{Decoder, Encoder, Json}
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class SystemInfoResource(
    messageBus: MessageBusPublisher,
    authNamespace: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[Uuid]
)(implicit db: Database, actorSystem: ActorSystem, ec: ExecutionContext) {
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val NetworkInfoEncoder: Encoder[NetworkInfo] = Encoder.instance { x =>
    import io.circe.syntax._
    Json.obj(
      "local_ipv4" -> x.localIpV4.asJson,
      "mac"        -> x.macAddress.asJson,
      "hostname"   -> x.hostname.asJson
    )
  }

  implicit val NetworkInfoDecoder: Decoder[Uuid => NetworkInfo] = Decoder.instance { c =>
    for {
      ip       <- c.get[String]("local_ipv4")
      mac      <- c.get[String]("mac")
      hostname <- c.get[String]("hostname")
    } yield (uuid: Uuid) => NetworkInfo(uuid, ip, hostname, mac)
  }

  def fetchSystemInfo(uuid: Uuid): Route = {
    val comp = db.run(SystemInfoRepository.findByUuid(uuid)).recover {
      case MissingSystemInfo => Json.obj()
    }
    complete(comp)
  }

  def createSystemInfo(ns: Namespace, uuid: Uuid, data: Json): Route = {
    val f = db
      .run(SystemInfoRepository.create(uuid, data))
      .andThen {
        case scala.util.Success(_) =>
          messageBus.publish(DeviceSystemInfoChanged(ns, uuid))
      }
    complete(Created -> f)
  }

  def updateSystemInfo(ns: Namespace, uuid: Uuid, data: Json): Route = {
    val f = db
      .run(SystemInfoRepository.update(uuid, data))
      .andThen {
        case scala.util.Success(_) =>
          messageBus.publish(DeviceSystemInfoChanged(ns, uuid))
      }
    complete(OK -> f)
  }

  def api: Route =
    (pathPrefix("devices") & authNamespace) { ns =>
      val scope = Scopes.devices(ns)
      deviceNamespaceAuthorizer { uuid =>
        pathPrefix("system_info") {
          pathEnd {
            scope.get {
              fetchSystemInfo(uuid)
            } ~
            scope.post {
              entity(as[Json]) { body =>
                createSystemInfo(ns.namespace, uuid, body)
              }
            } ~
            scope.put {
              entity(as[Json]) { body =>
                updateSystemInfo(ns.namespace, uuid, body)
              }
            }
          } ~
          path("network") {
            get {
              val networkInfo = db.run(SystemInfoRepository.getNetworkInfo(uuid))
              completeOrRecoverWith(networkInfo) {
                case MissingSystemInfo =>
                  complete(OK -> NetworkInfo(uuid, "", "", ""))
                case t =>
                  failWith(t)
              }
            } ~
            (put & entity(as[Uuid => NetworkInfo])) { payload =>
              val result = db
                .run(SystemInfoRepository.setNetworkInfo(payload(uuid)))
                .andThen {
                  case scala.util.Success(Done) =>
                    messageBus.publish(DeviceSystemInfoChanged(ns.namespace, uuid))
                }
              complete(NoContent -> result)
            }
          }
        }
      }
    }

  def mydeviceRoutes: Route = authNamespace { authedNs => // don't use this as a namespace
    (pathPrefix("mydevice") & UuidDirectives.extractUuid) { uuid =>
      (put & path("system_info") & authedNs.oauthScope(s"ota-core.{uuid.show}.write")) {
        entity(as[Json]) { body =>
          updateSystemInfo(authedNs.namespace, uuid, body)
        }
      }
    }
  }

  def route: Route = api ~ mydeviceRoutes
}
