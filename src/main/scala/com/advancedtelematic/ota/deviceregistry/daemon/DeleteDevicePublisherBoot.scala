package com.advancedtelematic.ota.deviceregistry.daemon

import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.slick.db.DatabaseConfig
import com.advancedtelematic.ota.deviceregistry.Boot.{config, system}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

// needs to run once to inform the director which devices are deleted
object DeleteDevicePublisherBoot extends BootApp with DatabaseConfig {
  lazy val projectName: String = buildinfo.BuildInfo.name
  implicit val _db = db

  lazy val messageBus = MessageBus.publisher(system, config)

  val publishingF = new DeletedDevicePublisher(messageBus).run.map { res =>
    log.info(s"Migration finished $res")
  }

  Await.result(publishingF, Duration.Inf)
}
