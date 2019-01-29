package com.advancedtelematic.ota.deviceregistry.data

import cats.Show
import io.circe.{Decoder, Encoder}

final case class DeviceOemId(value: String) extends AnyVal

object DeviceOemId {
  implicit val showDeviceOemId: Show[DeviceOemId] = _.value

  implicit val deviceIdEncoder: Encoder[DeviceOemId] = Encoder.encodeString.contramap[DeviceOemId](_.value)
  implicit val deviceIdDecoder: Decoder[DeviceOemId] = Decoder.decodeString.map(DeviceOemId.apply)

  implicit val deviceIdOrdering: Ordering[DeviceOemId] = (id1, id2) => id1.value compare id2.value
}