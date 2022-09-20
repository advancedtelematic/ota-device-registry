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

package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import akka.actor.Scheduler
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceSeen
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.messages.DeviceActivated
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.MySQLProfile.api._

class DeviceSeenListener(messageBus: MessageBusPublisher)
                        (implicit db: Database, ec: ExecutionContext, scheduler: Scheduler) extends MsgOperation[DeviceSeen] {

  val _logger = LoggerFactory.getLogger(this.getClass)

  override def apply(msg: DeviceSeen): Future[Done] =
    db.runWithRetry(DeviceRepository.updateLastSeen(msg.uuid, msg.lastSeen))
      .flatMap {
        case (activated, ns) =>
          if (activated) {
            messageBus
              .publishSafe(DeviceActivated(ns, msg.uuid, msg.lastSeen))
              .flatMap { _ =>
                db.runWithRetry(DeviceRepository.setDeviceStatus(msg.uuid, DeviceStatus.UpToDate))
              }
          } else {
            Future.successful(Done)
          }
      }
      .recover {
        case Errors.MissingDevice =>
          _logger.warn(s"Ignoring event for missing or deleted device: $msg")
        case ex =>
          _logger.warn(s"Could not process $msg", ex)
      }
      .map { _ =>
        Done
      }
}
