/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceSeen
import com.advancedtelematic.ota.deviceregistry.data.{DeviceStatus, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.messages.DeviceActivated
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

object DeviceSeenListener {

  val _logger = LoggerFactory.getLogger(this.getClass)

  def action(messageBus: MessageBusPublisher)(msg: DeviceSeen)(implicit db: Database,
                                                               ec: ExecutionContext): Future[Done] =
    DeviceRepository
      .updateLastSeen(Uuid.fromJava(msg.uuid.uuid), msg.lastSeen)
      .flatMap {
        case (activated, ns) =>
          if (activated) {
            messageBus
              .publishSafe(DeviceActivated(ns, Uuid.fromJava(msg.uuid.uuid), msg.lastSeen))
              .flatMap { _ =>
                DeviceRepository.setDeviceStatus(Uuid.fromJava(msg.uuid.uuid), DeviceStatus.UpToDate)
              }
          } else {
            Future.successful(Done)
          }
      }
      .recover {
        case Errors.MissingDevice =>
          _logger.warn(s"Ignore event for missing or deleted device: $msg")
        case ex =>
          _logger.warn(s"Could not process $msg", ex)
      }
      .map { _ =>
        Done
      }
}
