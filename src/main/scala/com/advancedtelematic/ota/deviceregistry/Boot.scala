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
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.settings.{ParserSettings, ServerSettings}
import com.advancedtelematic.libats.auth.NamespaceDirectives
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.Limit
import com.advancedtelematic.libats.http._
import com.advancedtelematic.libats.http.tracing.Tracing
import com.advancedtelematic.libats.messaging._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
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

  val maxAllowedDeviceEventsLimit = Limit(_config.getLong("maxAllowedDeviceEventsLimit"))
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
  implicit val scheduler: Scheduler = system.scheduler

  val authNamespace = NamespaceDirectives.fromConfig()

  private val namespaceAuthorizer = AllowUUIDPath.deviceUUID(authNamespace, deviceAllowed)

  private def deviceAllowed(deviceId: DeviceId): Future[Namespace] =
    db.runWithRetry(DeviceRepository.deviceNamespace(deviceId))

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
