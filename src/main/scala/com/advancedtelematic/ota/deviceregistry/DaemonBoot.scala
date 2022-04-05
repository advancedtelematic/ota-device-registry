package com.advancedtelematic.ota.deviceregistry

import akka.actor.Scheduler
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.messaging.{MessageBus, MessageListenerSupport}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeleteDeviceRequest, DeviceEventMessage, DeviceSeen, DeviceUpdateEvent, EcuReplacement}
import com.advancedtelematic.libats.slick.db.{BootMigrations, CheckMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.DbHealthResource
import com.advancedtelematic.metrics.prometheus.PrometheusMetricsSupport
import com.advancedtelematic.metrics.{MetricsSupport, MonitoredBusListenerSupport}
import com.advancedtelematic.ota.deviceregistry.daemon.{DeleteDeviceListener, DeviceEventListener, DeviceSeenListener, DeviceUpdateEventListener, EcuReplacementListener}

object DaemonBoot extends BootApp
  with DatabaseConfig
  with BootMigrations
  with CheckMigrations
  with MessageListenerSupport
  with MonitoredBusListenerSupport
  with MetricsSupport
  with PrometheusMetricsSupport
  with VersionInfo {

  implicit val _db = db
  implicit val scheduler: Scheduler = system.scheduler

  lazy val messageBus = MessageBus.publisher(system, config)

  log.info("Starting daemon service")

  startMonitoredListener[DeviceSeen](new DeviceSeenListener(messageBus))
  startMonitoredListener[DeviceEventMessage](new DeviceEventListener)
  startMonitoredListener[DeleteDeviceRequest](new DeleteDeviceListener)
  startMonitoredListener[DeviceUpdateEvent](new DeviceUpdateEventListener(messageBus))
  startMonitoredListener[EcuReplacement](new EcuReplacementListener)

  val routes: Route = versionHeaders(version) {
    DbHealthResource(versionMap).route
  } ~ prometheusMetricsRoutes

  val host = config.getString("server.host")
  val port = config.getInt("server.port")

  Http().bindAndHandle(routes, host, port)
}
