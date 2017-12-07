/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directive1, Directives, Route}
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, NamespaceDirectives}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http._
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.http.DefaultRejectionHandler.rejectionHandler
import com.advancedtelematic.libats.messaging.{MessageBus, MessageBusPublisher, MessageListener}
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceSeen
import com.advancedtelematic.libats.slick.db.{BootMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.ota.deviceregistry.daemon.{DeviceSeenListener, DeviceUpdateStatusListener}
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.messages.UpdateSpec
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Base API routing class.
  * @see {@linktourl http://advancedtelematic.github.io/rvi_sota_server/dev/api.html}
  */
class DeviceRegistryRoutes(
    namespaceExtractor: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[Uuid],
    messageBus: MessageBusPublisher
)(implicit db: Database, system: ActorSystem, mat: ActorMaterializer, exec: ExecutionContext)
    extends Directives {

  val route: Route = pathPrefix("api" / "v1") {
    handleRejections(rejectionHandler) {
      ErrorHandler.handleErrors {
        new DevicesResource(namespaceExtractor, messageBus, deviceNamespaceAuthorizer).route ~
        new SystemInfoResource(namespaceExtractor, deviceNamespaceAuthorizer).route ~
        new PublicCredentialsResource(namespaceExtractor, messageBus, deviceNamespaceAuthorizer).route ~
        new GroupsResource(namespaceExtractor, deviceNamespaceAuthorizer).route
      }
    }
  }
}

object Boot
    extends BootApp
    with Directives
    with BootMigrations
    with VersionInfo
    with DatabaseConfig
    with MetricsSupport
    with DatabaseMetrics {

  import VersionDirectives._
  import UuidDirectives._

  implicit val _db = db

  val authNamespace = NamespaceDirectives.fromConfig()

  private val namespaceAuthorizer = allowExtractor(authNamespace, extractUuid, deviceAllowed)

  private def deviceAllowed(deviceId: Uuid): Future[Namespace] =
    db.run(DeviceRepository.deviceNamespace(deviceId))

  lazy val messageBus =
    MessageBus.publisher(system, config) match {
      case Right(v) => v
      case Left(error) =>
        log.error("Could not initialize message bus publisher", error)
        MessageBusPublisher.ignore
    }

  val routes: Route =
  versionHeaders(version) {
    new DeviceRegistryRoutes(authNamespace, namespaceAuthorizer, messageBus).route
  } ~ DbHealthResource(versionMap).route

  val updateSpecListener =
    system.actorOf(
      MessageListener
        .props[UpdateSpec](system.settings.config, DeviceUpdateStatusListener.action(messageBus), metricRegistry)
    )
  updateSpecListener ! Subscribe

  val deviceSeenListener =
    system.actorOf(
      MessageListener
        .props[DeviceSeen](system.settings.config, DeviceSeenListener.action(messageBus), metricRegistry)
    )
  deviceSeenListener ! Subscribe

  val host = config.getString("server.host")
  val port = config.getInt("server.port")
  Http().bindAndHandle(routes, host, port)

  log.info(s"device registry started at http://$host:$port/")

  sys.addShutdownHook {
    Try(db.close())
    Try(system.terminate())
  }
}
