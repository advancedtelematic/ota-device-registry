package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.EcuImage
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CreateDevice, CreateEcu, Ecu, InstallationStat, SoftwareImage, UpdateDevice}
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import io.circe.{Decoder, Encoder}

object Codecs {

  implicit val deviceIdEncoder: Encoder[DeviceOemId] = Encoder.encodeString.contramap[DeviceOemId](_.underlying)
  implicit val deviceIdDecoder: Decoder[DeviceOemId] = Decoder.decodeString.map(DeviceOemId.apply)

  implicit val ecuEncoder: Encoder[Ecu] = io.circe.generic.semiauto.deriveEncoder[Ecu]
  implicit val ecuDecoder: Decoder[Ecu] = io.circe.generic.semiauto.deriveDecoder[Ecu]

  implicit val updateDeviceEncoder: Encoder[UpdateDevice] = io.circe.generic.semiauto.deriveEncoder
  implicit val updateDeviceDecoder: Decoder[UpdateDevice] = io.circe.generic.semiauto.deriveDecoder

  implicit val installationStatEncoder: Encoder[InstallationStat] = io.circe.generic.semiauto.deriveEncoder
  implicit val installationStatDecoder: Decoder[InstallationStat] = io.circe.generic.semiauto.deriveDecoder

  implicit val createEcuEncoder: Encoder[CreateEcu] = io.circe.generic.semiauto.deriveEncoder
  implicit val createEcuDecoder: Decoder[CreateEcu] = io.circe.generic.semiauto.deriveDecoder

  implicit val createDeviceEncoder: Encoder[CreateDevice] = io.circe.generic.semiauto.deriveEncoder
  implicit val createDeviceDecoder: Decoder[CreateDevice] = io.circe.generic.semiauto.deriveDecoder

  implicit val ecuImageEncoder: Encoder[EcuImage] = io.circe.generic.semiauto.deriveEncoder
  implicit val ecuImageDecoder: Decoder[EcuImage] = io.circe.generic.semiauto.deriveDecoder

  implicit val softwareImageEncoder: Encoder[SoftwareImage] = io.circe.generic.semiauto.deriveEncoder
  implicit val softwareImageDecoder: Decoder[SoftwareImage] = io.circe.generic.semiauto.deriveDecoder

}
