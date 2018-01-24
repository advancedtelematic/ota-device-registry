/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.Group._
import eu.timepit.refined.api.{Refined, Validate}

case class Group(id: Uuid, groupName: Name, namespace: Namespace)

object Group {
  case class ValidName()

  type Name = Refined[String, ValidName]

  implicit val validGroupName: Validate.Plain[String, ValidName] =
    Validate.fromPredicate(
      name => name.length > 1 && name.length <= 100 && name.matches("^[a-zA-Z0-9]*$"),
      name => s"($name should be between two and a hundred alphanumeric characters long.)",
      ValidName()
    )

  implicit val EncoderInstance = {
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    io.circe.generic.semiauto.deriveEncoder[Group]
  }
}
