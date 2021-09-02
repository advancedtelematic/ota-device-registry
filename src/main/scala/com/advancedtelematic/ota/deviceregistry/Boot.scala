/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.settings.{ParserSettings, ServerSettings}
import com.advancedtelematic.libats.auth.NamespaceDirectives
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http._
import com.advancedtelematic.libats.http.tracing.Tracing
import com.advancedtelematic.libats.messaging._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.{CheckMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport
import com.advancedtelematic.metrics.{AkkaHttpConnectionMetrics, AkkaHttpRequestMetrics, MetricsSupport}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.http.`application/toml`
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.util.Try

trait Settings {
  private lazy val _config = ConfigFactory.load()

  val directorUri = Uri(_config.getString("director.uri"))

  val maxAllowedDeviceEventsLimit = _config.getInt("maxAllowedDeviceEventsLimit")
}

object Boot extends BootApp
  with AkkaHttpRequestMetrics
  with AkkaHttpConnectionMetrics
  with CheckMigrations
  with DatabaseConfig
  with DatabaseMetrics
  with Directives
  with MetricsSupport
  with PrometheusMetricsSupport
  with VersionInfo
  with Settings
  with ServiceHttpClientSupport {

  import VersionDirectives._

  implicit val _db = db

  val authNamespace = NamespaceDirectives.fromConfig()

  private val namespaceAuthorizer = AllowUUIDPath.deviceUUID(authNamespace, deviceAllowed)

  private def deviceAllowed(deviceId: DeviceId): Future[Namespace] =
    db.run(DeviceRepository.deviceNamespace(deviceId))

  lazy val messageBus = MessageBus.publisher(system, config)

  val tracing = Tracing.fromConfig(config, projectName)

  val routes: Route =
  (LogDirectives.logResponseMetrics("device-registry") & requestMetrics(metricRegistry) & versionHeaders(version)) {
    prometheusMetricsRoutes ~
      tracing.traceRequests { implicit serverRequestTracing =>
        new DeviceRegistryRoutes(authNamespace, namespaceAuthorizer, messageBus).route
      }
  } ~ DbHealthResource(versionMap, healthMetrics = Seq(new BusListenerMetrics(metricRegistry))).route

  val host = config.getString("server.host")
  val port = config.getInt("server.port")

  val parserSettings = ParserSettings(system).withCustomMediaTypes(`application/toml`.mediaType)
  val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

  Http().bindAndHandle(withConnectionMetrics(routes, metricRegistry), host, port, settings = serverSettings)

  log.info(s"device registry started at http://$host:$port/")

  sys.addShutdownHook {
    Try(db.close())
    Try(system.terminate())
  }
}
