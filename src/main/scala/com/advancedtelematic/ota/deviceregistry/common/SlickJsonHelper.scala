/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.common

import slick.jdbc.MySQLProfile.api._
import io.circe.Json
import io.circe.jawn._

trait SlickJsonHelper {
  implicit val jsonColumnType = MappedColumnType.base[Json, String](
    { json =>
      json.noSpaces
    }, { str =>
      parse(str).fold(_ => Json.Null, x => x)
    }
  )
}
