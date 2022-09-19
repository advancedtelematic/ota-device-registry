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

import cats.{Eq, Show}

final case class PackageId(name: PackageId.Name, version: PackageId.Version)

object PackageId {
  type Name    = String
  type Version = String

  implicit val EncoderInstance = io.circe.generic.semiauto.deriveEncoder[PackageId]
  implicit val DecoderInstance = io.circe.generic.semiauto.deriveDecoder[PackageId]

  /**
    * Use the underlying (string) ordering, show and equality for
    * package ids.
    */
  implicit val PackageIdOrdering: Ordering[PackageId] = new Ordering[PackageId] {
    override def compare(id1: PackageId, id2: PackageId): Int =
      id1.name + id1.version compare id2.name + id2.version
  }

  implicit val showInstance: Show[PackageId] =
    Show.show(id => s"${id.name}-${id.version}")

  implicit val eqInstance: Eq[PackageId] =
    Eq.fromUniversalEquals[PackageId]
}
