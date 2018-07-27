/*
 * Copyright (c) 2018 HERE Technologies
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import akka.actor.ActorSystem
import com.advancedtelematic.libats.messaging.MessageListener
import com.advancedtelematic.libats.messaging.daemon.MessageBusListenerActor.Subscribe
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.messages.DeleteDeviceRequest
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.Config
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeleteDeviceHandler(config: Config, db: Database, metrics: MetricRegistry)(
    implicit val ec: ExecutionContext,
    system: ActorSystem
) {

  def start()(implicit db: Database): Unit = {
    val listener = system.actorOf(MessageListener.props[DeleteDeviceRequest](system.settings.config, handle, metrics),
                                  "delete-device-handler")
    listener ! Subscribe
  }

  private[this] def handle(message: DeleteDeviceRequest)(implicit db: Database): Future[Done] =
    DeviceRepository.delete(message.namespace, message.uuid).map(_ => Done)
}
