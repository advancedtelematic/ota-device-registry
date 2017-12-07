/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import eu.timepit.refined.api.Refined
import org.scalacheck.{Arbitrary, Gen}

trait UuidGenerator {

  val genUuid: Gen[Uuid] = for {
    uuid <- Gen.uuid
  } yield Uuid(Refined.unsafeApply(uuid.toString))

  implicit lazy val arbUuid: Arbitrary[Uuid] = Arbitrary(genUuid)

}

object UuidGenerator extends UuidGenerator
