package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.messaging.{BusListenerMetrics, MessageBus, MessageListenerSupport}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceEventMessage, DeviceSeen, DeviceUpdateEvent}
import com.advancedtelematic.libats.slick.db.{BootMigrations, CheckMigrations, DatabaseConfig}
import com.advancedtelematic.libats.slick.monitoring.DbHealthResource
import com.advancedtelematic.ota.deviceregistry.daemon.{DeleteDeviceHandler, DeviceEventListener, DeviceSeenListener, DeviceUpdateEventListener}
import com.advancedtelematic.ota.deviceregistry.messages.DeleteDeviceRequest

object DaemonBoot extends BootApp
  with DatabaseConfig
  with BootMigrations
  with CheckMigrations
  with MessageListenerSupport
  with MetricsSupport
  with VersionInfo {

  implicit val _db = db

  lazy val messageBus = MessageBus.publisher(system, config)

  log.info("Starting daemon service")

  startListener[DeviceSeen](new DeviceSeenListener(messageBus))
  startListener[DeviceEventMessage](new DeviceEventListener())
  startListener[DeleteDeviceRequest](new DeleteDeviceHandler())
  startListener[DeviceUpdateEvent](new DeviceUpdateEventListener(messageBus))

  val routes: Route = versionHeaders(version) {
    DbHealthResource(versionMap, healthMetrics = Seq(new BusListenerMetrics(metricRegistry))).route
  }

  val host = config.getString("server.host")
  val port = config.getInt("server.port")

  Http().bindAndHandle(routes, host, port)
}
