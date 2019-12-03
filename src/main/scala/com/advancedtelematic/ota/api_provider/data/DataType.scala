package com.advancedtelematic.ota.api_provider.data

import java.time.Instant

import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientHashes, ClientTargetItem}
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
import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.ota.api_provider.data.DataType.ApiDeviceUpdateEventName.ApiDeviceUpdateEventName
import com.advancedtelematic.ota.api_provider.client.DirectorClient._


object DataType {

  import io.circe.generic.semiauto._

  case class SoftwareVersion(filename: TargetFilename, hashes: ClientHashes, length: Long)

  case class PrimaryEcu(ecuId: EcuIdentifier, installedSoftwareVersion: SoftwareVersion)

  case class ListingDevice(uuid: DeviceId, deviceId: DeviceOemId)

  case class ApiDevice(deviceId: DeviceOemId,
                       uuid: DeviceId,
                       name: DeviceName,
                       lastSeen: Option[Instant],
                       status: DeviceStatus,
                       primaryEcu: Option[PrimaryEcu])


  type ApiUpdateId = CorrelationId

  object ApiDeviceUpdateEventName extends Enumeration {
    type ApiDeviceUpdateEventName = Value

    val DownloadComplete,
    DownloadStarted,
    DownloadCompleted,
    InstallationStarted,
    InstallationApplied,
    InstallationCompleted,
    DevicePaused,
    DeviceResumed,
    Accepted,
    Declined,
    Postponed,
    InstallationComplete = Value
  }

  case class ApiDeviceEvent(ecuId: Option[EcuIdentifier], updateId: Option[ApiUpdateId], name: ApiDeviceUpdateEventName,
                            receivedTime: Instant, deviceTime: Instant)

  case class ApiDeviceEvents(deviceUuid: DeviceId, events: Vector[ApiDeviceEvent])

  case class QueueItem(deviceUuid: DeviceId, updateId: ApiUpdateId, ecus: Map[EcuIdentifier, SoftwareVersion])

  implicit val softwareVersionCodec = deriveCodec[SoftwareVersion]

  implicit val queueItemCodec = deriveCodec[QueueItem]

  implicit val apiDeviceUpdateEventNameCodec = io.circe.Codec.codecForEnumeration(ApiDeviceUpdateEventName)

  implicit val apiDeviceUpdateCodec = deriveCodec[ApiDeviceEvent]

  implicit val apiUpdateStatusCodec = deriveCodec[ApiDeviceEvents]

  implicit val primaryEcuEncoder = deriveEncoder[PrimaryEcu].mapJson(_.dropNullValuesDeep)
  implicit val primaryEcuDecoder = deriveDecoder[PrimaryEcu]

  implicit val apiDeviceCodec = deriveCodec[ApiDevice]

  implicit val listingDeviceCodec = deriveCodec[ListingDevice]
}
