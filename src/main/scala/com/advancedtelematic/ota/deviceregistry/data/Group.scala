/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.util.UUID

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.UUIDKey.{UUIDKey, UUIDKeyObj}
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupId, _}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import eu.timepit.refined.api.{Refined, Validate}
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.api._

case class Group(id: GroupId,
                 groupName: Name,
                 namespace: Namespace,
                 groupType: GroupType,
                 expression: Option[GroupExpression] = None)

object GroupType extends Enumeration {
  type GroupType = Value

  val static, dynamic = Value

  implicit val GroupTypeMapper = MappedColumnType.base[GroupType, String](gt => gt.toString, s => GroupType.withName(s))

  implicit val JsonEncoder = Encoder.enumEncoder(GroupType)
  implicit val JsonDecoder = Decoder.enumDecoder(GroupType)
}

object Group {

  final case class GroupId(uuid: UUID) extends UUIDKey
  object GroupId                       extends UUIDKeyObj[GroupId]

  case class ValidName()
  type Name = Refined[String, ValidName]
  implicit val nameOrdering: Ordering[Name] = Ordering.by[Name, String](_.value.toLowerCase)

  implicit val validGroupName: Validate.Plain[String, ValidName] =
    Validate.fromPredicate(
      name => name.length > 1 && name.length <= 100,
      name => s"($name should be between two and a hundred alphanumeric characters long.)",
      ValidName()
    )

  case class ValidExpression()
  type GroupExpression = Refined[String, ValidExpression]

  implicit val validGroupExpression: Validate.Plain[String, ValidExpression] =
    Validate.fromPartial(
      expression => {
        if (expression.length < 1 || expression.length > 200)
          throw new IllegalArgumentException("The expression is too small or too big.")

        GroupExpressionParser.parse(expression) match {
          case Left(err) => throw err
          case Right(_)  => expression
        }
      },
       "group expression",
      ValidExpression()
    )

  implicit val EncoderInstance = {
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    io.circe.generic.semiauto.deriveEncoder[Group]
  }
}

object SortBy {
  sealed trait SortBy
  case object Name      extends SortBy
  case object CreatedAt extends SortBy
}