package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.ota.deviceregistry.data.DataType._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

object Codecs {
  implicit val deviceTEncoder: Encoder[DeviceT] = deriveEncoder[DeviceT]
  implicit val deviceTDecoder: Decoder[DeviceT] = deriveDecoder[DeviceT]

  implicit val createGroupEncoder: Encoder[CreateGroup] = deriveEncoder
  implicit val createGroupDecoder: Decoder[CreateGroup] = deriveDecoder

  implicit val updateDeviceEncoder: Encoder[UpdateDevice] = deriveEncoder[UpdateDevice]
  implicit val updateDeviceDecoder: Decoder[UpdateDevice] = deriveDecoder[UpdateDevice]

  implicit val installationStatEncoder: Encoder[InstallationStat] = deriveEncoder[InstallationStat]
  implicit val installationStatDecoder: Decoder[InstallationStat] = deriveDecoder[InstallationStat]

  implicit val activeDeviceCountEncoder: Encoder[ActiveDeviceCount] = Encoder.encodeInt.contramap[ActiveDeviceCount](_.deviceCount)
  implicit val activeDeviceCountDecoder: Decoder[ActiveDeviceCount] = Decoder.decodeInt.map(ActiveDeviceCount.apply)
}
