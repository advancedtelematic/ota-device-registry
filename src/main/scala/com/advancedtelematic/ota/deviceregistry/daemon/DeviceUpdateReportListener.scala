package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import akka.actor.ActorSystem
import com.advancedtelematic.libats.messaging.MessageListener
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.libtuf_server.data.Messages.DeviceUpdateReport
import com.advancedtelematic.ota.deviceregistry.data.DataType.CorrelationId
import com.advancedtelematic.ota.deviceregistry.db.UpdateReportRepository
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.Config
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceUpdateReportListener(config: Config, db: Database, metrics: MetricRegistry)
                                (implicit val ec: ExecutionContext, system: ActorSystem) {
  def start(): Unit = {
    val eventsListener = system.actorOf(
      MessageListener
        .props[DeviceUpdateReport](system.settings.config, handle, metrics)
    )
    eventsListener ! Subscribe
  }

  private[this] def handle(message: DeviceUpdateReport): Future[Done] = db.run {
    // FIXME the message will eventually contain a correlationId, not an updateId.
    val correlationId = CorrelationId.from(message.updateId)
    UpdateReportRepository
      .saveUpdateReport(correlationId, message.device, message.resultCode, message.operationResult)
      .map(_ => Done)
  }

}
