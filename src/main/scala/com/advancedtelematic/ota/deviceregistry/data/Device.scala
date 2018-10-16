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

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import cats.Show
import cats.syntax.show._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.Device.{DeviceName, DeviceOemId, DeviceType}
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus._
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.{Decoder, Encoder}

final case class Device(namespace: Namespace,
                        uuid: DeviceId,
                        oemId: DeviceOemId,
                        name: DeviceName,
                        deviceType: DeviceType = DeviceType.Other,
                        lastSeen: Option[Instant] = None,
                        createdAt: Instant,
                        activatedAt: Option[Instant] = None,
                        deviceStatus: DeviceStatus = NotSeen) {

  def toResponse: DeviceT = DeviceT(oemId, name, deviceType)
}

object Device {

  final case class DeviceOemId(underlying: String) extends AnyVal
  implicit val showDeviceOemId: Show[DeviceOemId] = deviceOemId => deviceOemId.underlying

  case class ValidDeviceName()
  type DeviceName = Refined[String, ValidDeviceName]
  implicit val validDeviceName: Validate.Plain[String, ValidDeviceName] =
    Validate.fromPredicate(
      name => name.length < 200,
      name => s"$name is not a valid DeviceName since it is longer than 200 characters",
      ValidDeviceName()
    )

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

    implicit val JsonEncoder = Encoder.enumEncoder(DeviceType)
    implicit val JsonDecoder = Decoder.enumDecoder(DeviceType)
  }

  implicit val showDeviceType = Show.fromToString[DeviceType.Value]

  implicit val showDevice: Show[Device] = Show.show[Device] {
    case d if d.deviceType == DeviceType.Vehicle =>
      s"Vehicle: uuid=${d.uuid.show}, VIN=${d.oemId}, lastSeen=${d.lastSeen}"
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
