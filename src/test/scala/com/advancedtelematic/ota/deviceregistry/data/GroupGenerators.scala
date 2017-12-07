/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.data.DataType.Namespace
import eu.timepit.refined.api.Refined
import org.scalacheck.{Arbitrary, Gen}

trait GroupGenerators {

  private lazy val defaultNs: Namespace = Namespace("default")

  val genGroupName: Gen[Group.Name] = for {
    strLen <- Gen.choose(2, 100)
    name   <- Gen.listOfN[Char](strLen, Arbitrary.arbChar.arbitrary)
  } yield Refined.unsafeApply(name.mkString)

  def genGroupInfo: Gen[Group] =
    for {
      name <- genGroupName
    } yield Group(Uuid.generate(), name, defaultNs)

  implicit lazy val arbGroupName: Arbitrary[Group.Name] = Arbitrary(genGroupName)
  implicit lazy val arbGroupInfo: Arbitrary[Group]      = Arbitrary(genGroupInfo)
}

object GroupGenerators extends GroupGenerators
