/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.time.{Instant, OffsetDateTime}

import cats.Show
import cats.syntax.show._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data
import com.advancedtelematic.ota.deviceregistry.data.Device.{DeviceId, MarketCode, DeviceName, DeviceType}
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus._
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.{Decoder, Encoder}

final case class Device(namespace: Namespace,
                        uuid: Uuid,
                        deviceName: DeviceName,
                        deviceId: Option[DeviceId] = None,
                        deviceType: DeviceType = DeviceType.Other,
                        lastSeen: Option[Instant] = None,
                        createdAt: Instant,
                        activatedAt: Option[Instant] = None,
                        deviceStatus: DeviceStatus = NotSeen,
                        marketCode: Option[MarketCode] = None) {

  // TODO: Use org.genivi.sota.core.data.client.ResponseEncoder
  def toResponse: DeviceT = data.DeviceT(deviceName, Some(uuid), deviceId, deviceType)
}

object Device {

  final case class DeviceId(underlying: String) extends AnyVal
  implicit val showDeviceId = new Show[DeviceId] {
    def show(deviceId: DeviceId) = deviceId.underlying
  }

  final case class MarketCode(underlying: String) extends AnyVal
  implicit val showDeviceMarketCode = new Show[MarketCode] {
    def show(marketCode: MarketCode): String = marketCode.underlying
  }

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

  implicit val DeviceIdOrdering: Ordering[DeviceId] = (id1: DeviceId, id2: DeviceId) =>
    id1.underlying compare id2.underlying

  implicit def DeviceOrdering(implicit ord: Ordering[Uuid]): Ordering[Device] =
    (d1: Device, d2: Device) => ord.compare(d1.uuid, d2.uuid)

  implicit val showOffsetDateTable = new Show[OffsetDateTime] {
    def show(odt: OffsetDateTime) = odt.toString
  }

  case class ActiveDeviceCount(deviceCount: Int) extends AnyVal

  object ActiveDeviceCount {
    implicit val EncoderInstance = Encoder.encodeInt.contramap[ActiveDeviceCount](_.deviceCount)
    implicit val DecoderInstance = Decoder.decodeInt.map(ActiveDeviceCount.apply)
  }
}
