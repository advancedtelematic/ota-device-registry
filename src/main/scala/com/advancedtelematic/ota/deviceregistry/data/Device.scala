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

package com.advancedtelematic.ota.deviceregistry.data

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import cats.Show
import cats.syntax.show._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.Device.{DeviceOemId, DeviceType}
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus._
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

  final case class DeviceOemId(underlying: String) extends AnyVal
  implicit val showDeviceOemId: Show[DeviceOemId] = deviceId => deviceId.underlying

  type DeviceType = DeviceType.DeviceType

  final object DeviceType extends Enumeration {
    // TODO: We should encode Enums as strings, not Ints
    // Moved this from SlickEnum, because this should **NOT** be used
    // It's difficult to read this when reading from the database and the Id is not stable when we add/remove
    // values from the enum
    import slick.jdbc.MySQLProfile.MappedJdbcType
    import slick.jdbc.MySQLProfile.api._

    implicit val enumMapper = MappedJdbcType.base[Value, Int](_.id, this.apply)

    type DeviceType = Value
    val Other, Vehicle = Value

    implicit val JsonEncoder = Encoder.encodeEnumeration(DeviceType)
    implicit val JsonDecoder = Decoder.decodeEnumeration(DeviceType)
  }

  implicit val showDeviceType = Show.fromToString[DeviceType.Value]

  implicit val showDevice: Show[Device] = Show.show[Device] {
    case d if d.deviceType == DeviceType.Vehicle =>
      s"Vehicle: uuid=${d.uuid.show}, VIN=${d.deviceId}, lastSeen=${d.lastSeen}"
    case d => s"Device: uuid=${d.uuid.show}, lastSeen=${d.lastSeen}"
  }

  implicit val EncoderInstance = {
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    io.circe.generic.semiauto.deriveEncoder[Device]
  }
  implicit val DecoderInstance = {
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    io.circe.generic.semiauto.deriveDecoder[Device]
  }

  implicit val DeviceIdOrdering: Ordering[DeviceOemId] = (id1, id2) => id1.underlying compare id2.underlying

  implicit def DeviceOrdering(implicit ord: Ordering[UUID]): Ordering[Device] =
    (d1, d2) => ord.compare(d1.uuid.uuid, d2.uuid.uuid)

  implicit val showOffsetDateTable: Show[OffsetDateTime] = odt => odt.toString

  case class ActiveDeviceCount(deviceCount: Int) extends AnyVal

  object ActiveDeviceCount {
    implicit val EncoderInstance = Encoder.encodeInt.contramap[ActiveDeviceCount](_.deviceCount)
    implicit val DecoderInstance = Decoder.decodeInt.map(ActiveDeviceCount.apply)
  }
}
