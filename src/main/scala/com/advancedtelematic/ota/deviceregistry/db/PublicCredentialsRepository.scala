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

package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object PublicCredentialsRepository {
  case class DevicePublicCredentials(device: DeviceId, typeCredentials: CredentialsType, credentials: Array[Byte])

  class PublicCredentialsTable(tag: Tag) extends Table[DevicePublicCredentials](tag, "DevicePublicCredentials") {
    def device            = column[DeviceId]("device_uuid")
    def typeCredentials   = column[CredentialsType]("type_credentials")
    def publicCredentials = column[Array[Byte]]("public_credentials")

    def * =
      (device, typeCredentials, publicCredentials).shaped <>
      ((DevicePublicCredentials.apply _).tupled, DevicePublicCredentials.unapply)

    def pk = primaryKey("device_uuid", device)
  }

  val allPublicCredentials = TableQuery[PublicCredentialsTable]

  def findByUuid(uuid: DeviceId)(implicit ec: ExecutionContext): DBIO[DevicePublicCredentials] =
    allPublicCredentials
      .filter(_.device === uuid)
      .result
      .failIfNotSingle(Errors.MissingDevicePublicCredentials)

  def update(uuid: DeviceId, cType: CredentialsType, creds: Array[Byte])(
      implicit ec: ExecutionContext
  ): DBIO[Unit] =
    allPublicCredentials
      .insertOrUpdate(DevicePublicCredentials(uuid, cType, creds))
      .map(_ => ())

  def delete(uuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Int] =
    allPublicCredentials.filter(_.device === uuid).delete
}
