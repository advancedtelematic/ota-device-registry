package com.advancedtelematic.ota.api_translator.data

import java.time.Instant

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.DeviceName
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus

object DataType {
  import com.advancedtelematic.ota.deviceregistry.data.Codecs._
  import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId._
  import com.advancedtelematic.libats.codecs.CirceAnyVal._
  import io.circe.generic.semiauto._

  case class ApiDevice(oemId: DeviceOemId,
                       id: DeviceId,
                       name: DeviceName,
                       lastSeen: Option[Instant],
                       status: DeviceStatus
                      )



  implicit val apiDeviceCodec = io.circe.generic.semiauto.deriveCodec[ApiDevice]
}
