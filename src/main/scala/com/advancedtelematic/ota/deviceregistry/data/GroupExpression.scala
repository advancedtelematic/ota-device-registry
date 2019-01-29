package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.codecs.CirceValidatedGeneric
import com.advancedtelematic.libats.data.{ValidatedGeneric, ValidationError}
import io.circe.{Decoder, Encoder}

final case class GroupExpression private (value: String) extends AnyVal

object GroupExpression {

  implicit val validatedGroupExpression = new ValidatedGeneric[GroupExpression, String] {
    override def to(expression: GroupExpression): String                   = expression.value
    override def from(s: String): Either[ValidationError, GroupExpression] = apply(s)
  }

  def apply(s: String): Either[ValidationError, GroupExpression] =
    if (s.length < 1 || s.length > 200)
      Left(ValidationError("The expression is too small or too big."))
    else
      GroupExpressionParser.parse(s).fold(
        e => Left(ValidationError(e.desc)),
        _ => Right(new GroupExpression(s))
      )

  implicit val groupExpressionEncoder: Encoder[GroupExpression] = CirceValidatedGeneric.validatedGenericEncoder
  implicit val groupExpressionDecoder: Decoder[GroupExpression] = CirceValidatedGeneric.validatedGenericDecoder
}
