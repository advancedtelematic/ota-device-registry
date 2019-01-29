package com.advancedtelematic.ota.deviceregistry.data

import io.circe.{Decoder, Encoder}

object GroupType extends Enumeration {
  type GroupType = Value
  val static, dynamic = Value

  implicit val groupTypeEncoder: Encoder[GroupType] = Encoder.enumEncoder(GroupType)
  implicit val groupTypeDecoder: Decoder[GroupType] = Decoder.enumDecoder(GroupType)
}
