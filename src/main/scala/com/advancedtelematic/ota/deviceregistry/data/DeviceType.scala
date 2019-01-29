package com.advancedtelematic.ota.deviceregistry.data

import cats.Show
import com.advancedtelematic.ota.deviceregistry.data
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.MappedJdbcType
import slick.jdbc.MySQLProfile.api._

object DeviceType extends Enumeration {
  type DeviceType = Value
  val Other, Vehicle = Value

  implicit val deviceTypeEncoder: Encoder[DeviceType] = Encoder.enumEncoder(DeviceType)
  implicit val deviceTypeDecoder: Decoder[DeviceType] = Decoder.enumDecoder(DeviceType)

  implicit val deviceTypeShow: Show[DeviceType] = Show.fromToString[DeviceType]
}
