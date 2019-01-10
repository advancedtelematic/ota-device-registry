package com.advancedtelematic.ota.deviceregistry.data

import io.circe.{Decoder, Encoder}
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CreateDevice, CreateEcu, Ecu, InstallationStat, UpdateDevice}
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId

object Codecs {

  implicit val deviceIdEncoder = Encoder.encodeString.contramap[DeviceOemId](_.underlying)
  implicit val deviceIdDecoder = Decoder.decodeString.map(DeviceOemId.apply)

  implicit val ecuEncoder: Encoder[Ecu] = io.circe.generic.semiauto.deriveEncoder[Ecu]
  implicit val ecuDecoder: Decoder[Ecu] = io.circe.generic.semiauto.deriveDecoder[Ecu]

  implicit val updateDeviceEncoder = io.circe.generic.semiauto.deriveEncoder[UpdateDevice]
  implicit val updateDeviceDecoder = io.circe.generic.semiauto.deriveDecoder[UpdateDevice]

  implicit val installationStatEncoder = io.circe.generic.semiauto.deriveEncoder[InstallationStat]
  implicit val installationStatDecoder = io.circe.generic.semiauto.deriveDecoder[InstallationStat]

  implicit val createEcuEncoder = io.circe.generic.semiauto.deriveEncoder[CreateEcu]
  implicit val createEcuDecoder = io.circe.generic.semiauto.deriveDecoder[CreateEcu]

  implicit val createDeviceEncoder = io.circe.generic.semiauto.deriveEncoder[CreateDevice]
  implicit val createDeviceDecoder = io.circe.generic.semiauto.deriveDecoder[CreateDevice]

}
