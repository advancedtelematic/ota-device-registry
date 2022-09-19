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

import akka.actor.Scheduler

import java.time.Instant
import java.util.Base64
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, PublicCredentialsRepository}
import com.advancedtelematic.ota.deviceregistry.messages.{DeviceCreated, DevicePublicCredentialsSet}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT

import scala.concurrent.{ExecutionContext, Future}

object PublicCredentialsResource {
  final case class FetchPublicCredentials(uuid: DeviceId, credentialsType: CredentialsType, credentials: String)
  implicit val fetchPublicCredentialsEncoder =
    io.circe.generic.semiauto.deriveEncoder[FetchPublicCredentials]
}

class PublicCredentialsResource(
    authNamespace: Directive1[AuthedNamespaceScope],
    messageBus: MessageBusPublisher,
    deviceNamespaceAuthorizer: Directive1[DeviceId]
)(implicit db: Database, mat: Materializer, ec: ExecutionContext, scheduler: Scheduler) {
  import PublicCredentialsResource._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  lazy val base64Decoder = Base64.getDecoder()
  lazy val base64Encoder = Base64.getEncoder()

  def fetchPublicCredentials(uuid: DeviceId): Route =
    complete(db.runWithRetry(PublicCredentialsRepository.findByUuid(uuid)).map { creds =>
      FetchPublicCredentials(uuid, creds.typeCredentials, new String(creds.credentials))
    })

  def createDeviceWithPublicCredentials(ns: Namespace, devT: DeviceT): Route = {
    val act = devT.credentials match {
      case Some(credentials) => {
        val cType = devT.credentialsType.getOrElse(CredentialsType.PEM)
        val dbact = for {
          (created, uuid) <- DeviceRepository.findUuidFromUniqueDeviceIdOrCreate(ns, devT.deviceId, devT)
          _               <- PublicCredentialsRepository.update(uuid, cType, credentials.getBytes)
        } yield (created, uuid)

        for {
          (created, uuid) <- db.runWithRetry(dbact.transactionally)
          _ <- if (created) {
            messageBus.publish(
              DeviceCreated(ns, uuid, devT.deviceName, devT.deviceId, devT.deviceType, Instant.now())
            )
          } else { Future.successful(()) }
          _ <- messageBus.publish(
            DevicePublicCredentialsSet(ns, uuid, cType, credentials, Instant.now())
          )
        } yield uuid
      }
      case None => FastFuture.failed(Errors.RequestNeedsCredentials)
    }
    complete(act)
  }

  def api: Route =
    (pathPrefix("devices") & authNamespace) { ns =>
      val scope = Scopes.devices(ns)
      pathEnd {
        (scope.put & entity(as[DeviceT])) { devT =>
          createDeviceWithPublicCredentials(ns.namespace, devT)
        }
      } ~
      deviceNamespaceAuthorizer { uuid =>
        path("public_credentials") {
          scope.get {
            fetchPublicCredentials(uuid)
          }
        }
      }
    }

  val route: Route = api
}
