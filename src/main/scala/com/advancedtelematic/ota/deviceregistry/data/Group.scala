/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.UUIDKey.{UUIDKey, UUIDKeyObj}
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.api._

case class Group(id: GroupId,
                 groupName: GroupName,
                 namespace: Namespace,
                 createdAt: Instant,
                 groupType: GroupType,
                 expression: Option[GroupExpression] = None)

object GroupType extends Enumeration {
  type GroupType = Value

  val static, dynamic = Value

  implicit val groupTypeMapper = MappedColumnType.base[GroupType, String](_.toString, GroupType.withName)

  implicit val groupTypeEncoder: Encoder[GroupType] = Encoder.encodeEnumeration(GroupType)
  implicit val groupTypeDecoder: Decoder[GroupType] = Decoder.decodeEnumeration(GroupType)

  implicit val groupTypeUnmarshaller: Unmarshaller[String, GroupType] = Unmarshaller.strict(GroupType.withName)
}

object Group {

  final case class GroupId(uuid: UUID) extends UUIDKey
  object GroupId                       extends UUIDKeyObj[GroupId]

  implicit val groupEncoder: Encoder[Group] = deriveEncoder[Group]
  implicit val groupDecoder: Decoder[Group] = deriveDecoder[Group]
}

object SortBy {
  sealed trait SortBy
  case object Name      extends SortBy
  case object CreatedAt extends SortBy
}