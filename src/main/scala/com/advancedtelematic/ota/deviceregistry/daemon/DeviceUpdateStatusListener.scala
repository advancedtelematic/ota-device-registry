/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.daemon

import java.time.Instant

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.ota.deviceregistry.data.{DeviceStatus}
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.messages.{UpdateSpec, UpdateStatus}
import com.advancedtelematic.ota.deviceregistry.messages.UpdateStatus.UpdateStatus
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs._
import com.advancedtelematic.libats.messaging_datatype.Messages._
import MessageLike._
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceUpdateStatusListener.DeviceUpdateStatus._

import scala.concurrent.{ExecutionContext, Future}

object DeviceUpdateStatusListener {
  def currentDeviceStatus(lastSeen: Option[Instant], updateStatuses: Seq[(Instant, UpdateStatus)]): DeviceStatus =
    if (lastSeen.isEmpty) {
      DeviceStatus.NotSeen
    } else {
      val statuses = updateStatuses.sortBy(_._1).reverse.map(_._2)

      if (statuses.headOption.contains(UpdateStatus.Failed)) {
        DeviceStatus.Error
      } else if (!statuses.forall(
                   s =>
                     List(UpdateStatus.Canceled, UpdateStatus.Finished, UpdateStatus.Failed)
                       .contains(s)
                 )) {
        DeviceStatus.Outdated
      } else {
        DeviceStatus.UpToDate
      }
    }

  final case class DeviceUpdateStatus(namespace: Namespace,
                                      device: DeviceId,
                                      status: DeviceStatus,
                                      timestamp: Instant = Instant.now())

  object DeviceUpdateStatus {
    import cats.syntax.show._
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    implicit val MessageLikeInstance = MessageLike.derive[DeviceUpdateStatus](_.device.show)
  }

  def action(messageBus: MessageBusPublisher)
            (implicit db: Database, system: ExecutionContext): UpdateSpec => Future[Unit] = { msg: UpdateSpec =>
    val f = for {
      device <- DeviceRepository.findByUuid(msg.device)
      status = currentDeviceStatus(device.lastSeen, Seq((Instant.now(), msg.status)))
      _ <- DeviceRepository.setDeviceStatus(device.uuid, status)
    } yield (device, status)

    db.run(f).flatMap {
      case (device, status) =>
        messageBus
          .publish(DeviceUpdateStatus(device.namespace, device.uuid, status, Instant.now()))
    }
  }

}
