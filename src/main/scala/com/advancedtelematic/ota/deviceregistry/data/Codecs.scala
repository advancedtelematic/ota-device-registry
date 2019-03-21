package com.advancedtelematic.ota.deviceregistry.data

import io.circe.{Decoder, Encoder}
import com.advancedtelematic.libats.codecs.CirceCodecs.{refinedDecoder, refinedEncoder}
import com.advancedtelematic.libats.data.DataType.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.DataType.{DeviceT, InstallationStat, UpdateDevice}

object Codecs {
  implicit val deviceIdEncoder = Encoder.encodeString.contramap[DeviceOemId](_.value)
  implicit val deviceIdDecoder = Decoder.decodeString.map(DeviceOemId.apply)

  implicit val deviceTEncoder = io.circe.generic.semiauto.deriveEncoder[DeviceT]
  implicit val deviceTDecoder = io.circe.generic.semiauto.deriveDecoder[DeviceT]

  implicit val updateDeviceEncoder = io.circe.generic.semiauto.deriveEncoder[UpdateDevice]
  implicit val updateDeviceDecoder = io.circe.generic.semiauto.deriveDecoder[UpdateDevice]

  implicit val installationStatEncoder = io.circe.generic.semiauto.deriveEncoder[InstallationStat]
  implicit val installationStatDecoder = io.circe.generic.semiauto.deriveDecoder[InstallationStat]
}
