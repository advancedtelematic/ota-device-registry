package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.codecs.CirceValidatedGeneric.{validatedGenericDecoder, validatedGenericEncoder}
import com.advancedtelematic.libats.data.{ValidatedGeneric, ValidationError}
import io.circe.Codec

final case class DeviceTagName private(value: String) extends AnyVal

object DeviceTagName {

  implicit val validatedDeviceTagName = new ValidatedGeneric[DeviceTagName, String] {
    override def to(expression: DeviceTagName): String = expression.value
    override def from(s: String): Either[ValidationError, DeviceTagName] = apply(s)
  }

  def apply(s: String): Either[ValidationError, DeviceTagName] =
    if (s.length <= 15 && s.matches("[\\w ]+"))
      Right(new DeviceTagName(s))
    else
      Left(ValidationError(s"$s should contain between one and a fifteen alphanumeric, underscore or space characters."))

  implicit val deviceTagNameCodec: Codec[DeviceTagName] = Codec.from(validatedGenericDecoder, validatedGenericEncoder)

}
