/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
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
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceEventMessage
import com.advancedtelematic.ota.deviceregistry.db.EventJournal
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.Config
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceEventListener(config: Config, db: Database, metrics: MetricRegistry)(
    implicit val ec: ExecutionContext,
    system: ActorSystem
) {
  private[this] val journal = new EventJournal(db)

  def start(): Unit = {
    val eventsListener = system.actorOf(
      MessageListener
        .props[DeviceEventMessage](system.settings.config, handle, metrics)
    )
    eventsListener ! Subscribe
  }

  private[this] def handle(message: DeviceEventMessage): Future[Done] =
    journal.recordEvent(message.event).map(_ => Done)

}
