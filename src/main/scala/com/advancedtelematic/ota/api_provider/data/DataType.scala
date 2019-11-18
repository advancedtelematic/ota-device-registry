package com.advancedtelematic.ota.api_provider.data

import java.time.Instant

import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.ClientDataType.ClientTargetItem
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.DeviceName
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId._
import com.advancedtelematic.libats.codecs.JsonDropNullValues._

object DataType {

  import io.circe.generic.semiauto._

  case class InstalledTarget(filename: TargetFilename, target: ClientTargetItem)

  case class PrimaryEcu(ecuId: EcuIdentifier, installedTarget: InstalledTarget)

  case class ListingDevice(id: DeviceId, clientDeviceId: DeviceOemId)

  case class ApiDevice(clientDeviceId: DeviceOemId,
                       id: DeviceId,
                       name: DeviceName,
                       lastSeen: Option[Instant],
                       status: DeviceStatus,
                       primaryEcu: Option[PrimaryEcu])

  implicit val installedTargetCodec = deriveCodec[InstalledTarget]

  implicit val primaryEcuEncoder = deriveEncoder[PrimaryEcu].mapJson(_.dropNullValuesDeep)
  implicit val primaryEcuDecoder = deriveDecoder[PrimaryEcu]

  implicit val apiDeviceCodec = deriveCodec[ApiDevice]

  implicit val listingDeviceCodec = deriveCodec[ListingDevice]
}
