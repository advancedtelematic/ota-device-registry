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

import cats.syntax.show._
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import org.scalacheck.{Arbitrary, Gen}

/**
  * Created by vladimir on 16/03/16.
  */
trait DeviceIdGenerators {

  /**
    * For property based testing purposes, we need to explain how to
    * randomly generate (possibly invalid) VINs.
    *
    * @see [[https://www.scalacheck.org/]]
    */
  val genVin: Gen[DeviceOemId] =
    for {
      vin <- SemanticVin.genSemanticVin
    } yield DeviceOemId(vin.show)

  implicit lazy val arbVin: Arbitrary[DeviceOemId] =
    Arbitrary(genVin)

  val genVinChar: Gen[Char] =
    Gen.oneOf('A' to 'Z' diff List('I', 'O', 'Q'))

  val genInvalidDeviceId: Gen[DeviceOemId] = {

    val genTooLongVin: Gen[String] = for {
      n  <- Gen.choose(18, 100) // scalastyle:ignore magic.number
      cs <- Gen.listOfN(n, genVinChar)
    } yield cs.mkString

    val genTooShortVin: Gen[String] = for {
      n  <- Gen.choose(1, 16) // scalastyle:ignore magic.number
      cs <- Gen.listOfN(n, genVinChar)
    } yield cs.mkString

    val genNotAlphaNumVin: Gen[String] =
      Gen
        .listOfN(17, Arbitrary.arbitrary[Char])
        . // scalastyle:ignore magic.number
        suchThat(_.exists(!_.isLetterOrDigit))
        .flatMap(_.mkString)

    Gen
      .oneOf(genTooLongVin, genTooShortVin, genNotAlphaNumVin)
      .map(DeviceOemId)
  }

  def getInvalidVin: DeviceOemId =
    genInvalidDeviceId.sample.getOrElse(getInvalidVin)
}
