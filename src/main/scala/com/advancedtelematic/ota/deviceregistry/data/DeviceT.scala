/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceName
import io.circe.{Decoder, Encoder}

/*
 * Device transfer object
 */
// TODO: Use org.genivi.sota.core.data.client.ResponseEncoder
final case class DeviceT(
    deviceName: DeviceName,
    deviceUuid: Option[Uuid] = None,
    deviceId: Option[Device.DeviceId] = None,
    deviceType: Device.DeviceType = Device.DeviceType.Other,
    credentials: Option[String] = None,
    credentialsType: Option[CredentialsType] = None,
    marketCode: Option[Device.MarketCode] = None
)

object DeviceT {
  private[this] implicit val DevcieIdEncoder = Encoder.encodeString.contramap[Device.DeviceId](_.underlying)
  private[this] implicit val DevcieIdDecoder = Decoder.decodeString.map(Device.DeviceId.apply)
  private[this] implicit val DeviceMarketCodeEncoder =
    Encoder.encodeString.contramap[Device.MarketCode](_.underlying)
  private[this] implicit val DeviceMarketCodeDecoder = Decoder.decodeString.map(Device.MarketCode.apply)
  import com.advancedtelematic.libats.codecs.CirceCodecs.{refinedDecoder, refinedEncoder}
  implicit val EncoderInstance = io.circe.generic.semiauto.deriveEncoder[DeviceT]
  implicit val DecoderInstance = io.circe.generic.semiauto.deriveDecoder[DeviceT]
}
