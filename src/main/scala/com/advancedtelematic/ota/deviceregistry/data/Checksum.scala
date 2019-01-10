package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.codecs.CirceValidatedGeneric
import com.advancedtelematic.libats.data.{ValidatedGeneric, ValidationError, ValidationUtils}
import io.circe.{Decoder, Encoder}

final case class Checksum private(value: String) extends AnyVal

object Checksum {

  implicit val validatedChecksum = new ValidatedGeneric[Checksum, String] {
    override def to(checksum: Checksum): String = checksum.value
    override def from(s: String): Either[ValidationError, Checksum] = apply(s)
  }

  def apply(s: String): Either[ValidationError, Checksum] =
    if (ValidationUtils.validHex(64, s))
      Right(new Checksum(s))
    else
      Left(ValidationError(s"'$s' should be a 64 characters long hex string."))

  implicit val checksumEncoder: Encoder[Checksum] = CirceValidatedGeneric.validatedGenericEncoder
  implicit val checksumDecoder: Decoder[Checksum] = CirceValidatedGeneric.validatedGenericDecoder

}
