package com.advancedtelematic.ota.deviceregistry.data

import io.circe.{Decoder, Encoder}
import com.advancedtelematic.libats.codecs.CirceCodecs.{refinedDecoder, refinedEncoder}

object Codecs {
  //    implicit val DecoderInstance = io.circe.Decoder.enumDecoder(UpdateStatus)
  //    implicit val EncoderInstance = io.circe.Encoder.enumEncoder(UpdateStatus)

  private[this] implicit val deviceIdEncoder = Encoder.encodeString.contramap[Device.DeviceId](_.underlying)
  private[this] implicit val deviceIdDecoder = Decoder.decodeString.map(Device.DeviceId.apply)

  implicit val deviceTEncoder = io.circe.generic.semiauto.deriveEncoder[DeviceT]
  implicit val deviceTDecoder = io.circe.generic.semiauto.deriveDecoder[DeviceT]
}
