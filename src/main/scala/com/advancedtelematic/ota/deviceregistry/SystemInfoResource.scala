/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry

import java.time.Instant
import akka.Done
import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import cats.syntax.option._
import cats.syntax.show._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors.RawError
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.Messages.{AktualizrConfigChanged, DeviceSystemInfoChanged}
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.ota.deviceregistry.common.Errors.{Codes, MissingSystemInfo}
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository.NetworkInfo
import com.advancedtelematic.ota.deviceregistry.http.`application/toml`
import io.circe.{Decoder, Encoder, Json}
import slick.jdbc.MySQLProfile.api._
import toml.Toml
import toml.Value.{Bool, Num, Str, Tbl}

import scala.concurrent.ExecutionContext
import scala.util.Try

case class AktualizrConfig(uptane: Uptane, pacman: Pacman)
case class Uptane(polling_sec: Int, force_install_completion: Boolean, secondary_preinstall_wait_sec: Option[Int])
case class Pacman(`type`: String)

object SystemInfoResource {
  def parseAktualizrConfigToml(s: String): Try[AktualizrConfig] = for {
    toml <- Toml.parse(s).left.map(err => new Exception(err._2)).toTry
    pacmanTable <- Try(toml.values("pacman").asInstanceOf[Tbl])
    pacmanType <- Try(pacmanTable.values("type").asInstanceOf[Str].value)
    uptaneTable <- Try(toml.values("uptane").asInstanceOf[Tbl])
    pollingSec <- Try(uptaneTable.values("polling_sec").asInstanceOf[Num].value.toInt)
    forceInstallCompletion <- Try(uptaneTable.values("force_install_completion").asInstanceOf[Bool].value)
    secondaryPreinstallWaitSec <- Try(uptaneTable.values.get("secondary_preinstall_wait_sec").map(_.asInstanceOf[Num].value.toInt))
  } yield(AktualizrConfig(Uptane(pollingSec, forceInstallCompletion, secondaryPreinstallWaitSec), Pacman(pacmanType)))
}
class SystemInfoResource(
    messageBus: MessageBusPublisher,
    authNamespace: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[DeviceId]
)(implicit db: Database, actorSystem: ActorSystem, ec: ExecutionContext, scheduler: Scheduler) {
  import SystemInfoResource._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val systemInfoUpdatePublisher = new SystemInfoUpdatePublisher(messageBus)

  implicit val NetworkInfoEncoder: Encoder[NetworkInfo] = Encoder.instance { x =>
    import io.circe.syntax._
    Json.obj(
      "local_ipv4" -> x.localIpV4.asJson,
      "mac"        -> x.macAddress.asJson,
      "hostname"   -> x.hostname.asJson
    )
  }

  implicit val NetworkInfoDecoder: Decoder[DeviceId => NetworkInfo] = Decoder.instance { c =>
    for {
      ip       <- c.get[String]("local_ipv4")
      mac      <- c.get[String]("mac")
      hostname <- c.get[String]("hostname")
    } yield (uuid: DeviceId) => NetworkInfo(uuid, ip, hostname, mac)
  }

  implicit val aktualizrConfigUnmarshaller: FromEntityUnmarshaller[AktualizrConfig] = Unmarshaller.stringUnmarshaller.map { s =>
    parseAktualizrConfigToml(s) match {
      case scala.util.Success(aktualizrConfig) => aktualizrConfig
      case scala.util.Failure(t) => throw RawError(Codes.MalformedInput, StatusCodes.BadRequest, t.getMessage)
    }
  }.forContentTypes(`application/toml`)

  def fetchSystemInfo(uuid: DeviceId): Route = {
    val comp = db.runWithRetry(SystemInfoRepository.findByUuid(uuid)).recover {
      case MissingSystemInfo => Json.obj()
    }
    complete(comp)
  }

  def createSystemInfo(ns: Namespace, uuid: DeviceId, data: Json): Route = {
    val f = db
      .runWithRetry(SystemInfoRepository.create(uuid, data))
      .andThen {
        case scala.util.Success(_) =>
          systemInfoUpdatePublisher.publishSafe(ns, uuid, data.some)
      }
    complete(Created -> f)
  }

  def updateSystemInfo(ns: Namespace, uuid: DeviceId, data: Json): Route = {
    val f = db
      .runWithRetry(SystemInfoRepository.update(uuid, data))
      .andThen {
        case scala.util.Success(_) =>
          systemInfoUpdatePublisher.publishSafe(ns, uuid, data.some)
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
              val networkInfo = db.runWithRetry(SystemInfoRepository.getNetworkInfo(uuid))
              completeOrRecoverWith(networkInfo) {
                case MissingSystemInfo =>
                  complete(OK -> NetworkInfo(uuid, "", "", ""))
                case t =>
                  failWith(t)
              }
            } ~
            (put & entity(as[DeviceId => NetworkInfo])) { payload =>
              val result = db
                .runWithRetry(SystemInfoRepository.setNetworkInfo(payload(uuid)))
                .andThen {
                  case scala.util.Success(Done) =>
                    messageBus.publish(DeviceSystemInfoChanged(ns.namespace, uuid, None))
                }
              complete(NoContent -> result)
            }
          } ~
          path("config") {
            pathEnd {
              post {
                entity(as[AktualizrConfig]) { config =>
                  val result = messageBus.publish(AktualizrConfigChanged(ns.namespace, uuid, config.uptane.polling_sec,
                                                                         config.uptane.secondary_preinstall_wait_sec,
                                                                         config.uptane.force_install_completion,
                                                                         config.pacman.`type`, Instant.now))
                  complete(result.map(_ => NoContent))
                }
              }
            }
          }
        }
      }
    }

  def mydeviceRoutes: Route = authNamespace { authedNs => // don't use this as a namespace
    pathPrefix("mydevice" / DeviceId.Path) { uuid =>
      (put & path("system_info") & authedNs.oauthScope(s"ota-core.${uuid.show}.write")) {
        entity(as[Json]) { body =>
          updateSystemInfo(authedNs.namespace, uuid, body)
        }
      }
    }
  }

  val route: Route = api ~ mydeviceRoutes
}
