/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

import cats.Show
import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus._
import com.advancedtelematic.ota.deviceregistry.data.DeviceType.DeviceType
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

final case class Device(namespace: Namespace,
                        uuid: DeviceId,
                        deviceName: DeviceName,
                        deviceId: DeviceOemId,
                        deviceType: DeviceType = DeviceType.Other,
                        lastSeen: Option[Instant] = None,
                        createdAt: Instant,
                        activatedAt: Option[Instant] = None,
                        deviceStatus: DeviceStatus = NotSeen)

object Device {

  implicit val deviceEncoder: Encoder[Device] = deriveEncoder[Device]
  implicit val deviceDecoder: Decoder[Device] = deriveDecoder[Device]

  implicit def DeviceOrdering(implicit ord: Ordering[UUID]): Ordering[Device] =
    (d1, d2) => ord.compare(d1.uuid.uuid, d2.uuid.uuid)

  implicit val showDevice: Show[Device] = Show.show[Device] {
    case d if d.deviceType == DeviceType.Vehicle =>
      s"Vehicle: uuid=${d.uuid.show}, VIN=${d.deviceId}, lastSeen=${d.lastSeen}"
    case d => s"Device: uuid=${d.uuid.show}, lastSeen=${d.lastSeen}"
  }

  implicit val showOffsetDateTable: Show[OffsetDateTime] = Show.fromToString

}
