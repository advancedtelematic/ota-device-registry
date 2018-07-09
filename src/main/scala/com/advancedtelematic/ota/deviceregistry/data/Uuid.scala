/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.util.UUID

import cats.Show
import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.api._

final case class Uuid(underlying: String Refined Uuid.Valid) {
  def toJava: UUID = UUID.fromString(underlying.value)
}

object Uuid {
  type Valid = eu.timepit.refined.string.Uuid

  implicit val showUuid = new Show[Uuid] {
    def show(uuid: Uuid) = uuid.underlying.value
  }

  implicit val UuidOrdering: Ordering[Uuid] = (uuid1: Uuid, uuid2: Uuid) =>
    uuid1.underlying.value compare uuid2.underlying.value

  def generate(): Uuid = Uuid(refineV[Valid](UUID.randomUUID.toString).right.get)

  def fromJava(uuid: UUID): Uuid = Uuid(refineV[Uuid.Valid](uuid.toString).right.get)

  implicit val customUuidEncoder: Encoder[Uuid] = Encoder[String].contramap(_.show)
  implicit val customUuidDecoder: Decoder[Uuid] =
    CirceCodecs.refinedDecoder[String, Uuid.Valid].map(Uuid(_))
  // Slick mapping
  implicit val uuidColumnType =
    MappedColumnType
      .base[Uuid, String](_.show, (s: String) => Uuid(refineV[Uuid.Valid](s).right.get))
}
