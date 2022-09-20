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
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceEventMessage
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.db.EventJournal
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import java.sql.SQLIntegrityConstraintViolationException
import scala.concurrent.{ExecutionContext, Future}

class DeviceEventListener()(implicit val db: Database, ec: ExecutionContext, scheduler: Scheduler) extends MsgOperation[DeviceEventMessage] {

  private[this] val journal = new EventJournal()
  private lazy val log = LoggerFactory.getLogger(this.getClass)


  override def apply(message: DeviceEventMessage): Future[Done] =
    journal.recordEvent(message.event).map(_ => Done).recover {
      case e: SQLIntegrityConstraintViolationException =>
        log.error(s"Can't record event $message", e)
        Done
    }
}