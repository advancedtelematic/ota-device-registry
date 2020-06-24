package com.advancedtelematic.ota.deviceregistry.data

import io.circe.{Decoder, Encoder}
import com.advancedtelematic.libats.codecs.CirceAnyVal.{anyValStringDecoder, anyValStringEncoder}
import com.advancedtelematic.ota.deviceregistry.data.DataType.{DeviceT, InstallationStat, PackageListItem, PackageListItemCount, RenameTagId, UpdateDevice}

object Codecs {
  private implicit val deviceIdEncoder = Encoder.encodeString.contramap[Device.DeviceOemId](_.underlying)
  private implicit val deviceIdDecoder = Decoder.decodeString.map(Device.DeviceOemId.apply)

  implicit val deviceTEncoder = io.circe.generic.semiauto.deriveEncoder[DeviceT]
  implicit val deviceTDecoder = io.circe.generic.semiauto.deriveDecoder[DeviceT]

  implicit val updateDeviceEncoder = io.circe.generic.semiauto.deriveEncoder[UpdateDevice]
  implicit val updateDeviceDecoder = io.circe.generic.semiauto.deriveDecoder[UpdateDevice]

  implicit val installationStatEncoder = io.circe.generic.semiauto.deriveEncoder[InstallationStat]
  implicit val installationStatDecoder = io.circe.generic.semiauto.deriveDecoder[InstallationStat]

  implicit val packageListItemCodec = io.circe.generic.semiauto.deriveCodec[PackageListItem]

  implicit val packageListItemCountCodec = io.circe.generic.semiauto.deriveCodec[PackageListItemCount]

  implicit val renameTagIdCodec = io.circe.generic.semiauto.deriveCodec[RenameTagId]
}
