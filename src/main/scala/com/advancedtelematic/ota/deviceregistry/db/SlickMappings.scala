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

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.slick.codecs.SlickEnumMapper
import com.advancedtelematic.ota.deviceregistry.data.DataType.{PackageListItemCount, IndexedEventType}
import com.advancedtelematic.ota.deviceregistry.data.{CredentialsType, GroupType, PackageId}
import slick.jdbc.MySQLProfile.api._

object SlickMappings {
  implicit val groupTypeMapper = SlickEnumMapper.enumMapper(GroupType)

  implicit val credentialsTypeMapper = SlickEnumMapper.enumMapper(CredentialsType)

  implicit val indexedEventTypeMapper = SlickEnumMapper.enumMapper(IndexedEventType)

  private[db] implicit val namespaceColumnType =
    MappedColumnType.base[Namespace, String](_.get, Namespace.apply)

  private[db] case class LiftedPackageId(name: Rep[PackageId.Name], version: Rep[PackageId.Version])

  private[db] implicit object LiftedPackageShape
    extends CaseClassShape(LiftedPackageId.tupled, (p: (PackageId.Name, PackageId.Version)) => PackageId(p._1, p._2))

  private[db] case class LiftedPackageListItemCount(packageId: LiftedPackageId, deviceCount: Rep[Int])
  private[db] implicit object ListedPackageListItemCountShape extends CaseClassShape(LiftedPackageListItemCount.tupled, PackageListItemCount.tupled)
}
