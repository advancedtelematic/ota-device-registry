package com.advancedtelematic.ota.deviceregistry.data

import io.circe.{Decoder, Encoder}
import com.advancedtelematic.libats.codecs.CirceCodecs.{refinedDecoder, refinedEncoder}
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT

object Codecs {
  private[this] implicit val deviceIdEncoder = Encoder.encodeString.contramap[Device.DeviceOemId](_.underlying)
  private[this] implicit val deviceIdDecoder = Decoder.decodeString.map(Device.DeviceOemId.apply)

  implicit val deviceTEncoder = io.circe.generic.semiauto.deriveEncoder[DeviceT]
  implicit val deviceTDecoder = io.circe.generic.semiauto.deriveDecoder[DeviceT]
}
