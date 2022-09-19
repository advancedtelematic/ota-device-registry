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

import cats.Show
import cats.syntax.show._
import org.scalacheck.Gen

// https://en.wikipedia.org/wiki/VIN

case class SemanticVin(
    wmi: SemanticVin.WMI,
    vehicleAttributes: SemanticVin.VehicleAttributes,
    checkDigit: SemanticVin.CheckDigit,
    modelYear: SemanticVin.ModelYear,
    plantCode: SemanticVin.PlantCode,
    sequentialNumber: SemanticVin.SequentialNumber
)
object SemanticVin {

  implicit val showSemanticVin: Show[SemanticVin] =
    Show.show { sv =>
      sv.wmi.show +
      sv.vehicleAttributes.show +
      sv.checkDigit.show +
      sv.modelYear.show +
      sv.plantCode.show +
      sv.sequentialNumber.toString
    }

  def genSemanticVin: Gen[SemanticVin] =
    for {
      wmi       <- genWMI
      vehAttr   <- genVehicleAttributes
      checkDig  <- Gen.const(D9)
      modelYr   <- genModelYear
      plantCode <- genPlantCode
      seqNum    <- Gen.choose(100000, 999999)
    } yield SemanticVin(wmi, vehAttr, checkDig, modelYr, plantCode, seqNum)

  // World manufacturer identifier (3 characters).
  sealed trait WMI

  final case object VolvoCars extends WMI

  implicit val showWMI: Show[WMI] =
    Show.show {
      case VolvoCars => "YV1"
    }

  def genWMI: Gen[WMI] =
    Gen.const(VolvoCars)

  // The attributes of the vehicle (5 characters).
  sealed trait VehicleAttributes

  // 1L engine.
  final case object ENG1L extends VehicleAttributes

  // 2L engine.
  final case object ENG2L extends VehicleAttributes

  implicit val showVehicleAttributes: Show[VehicleAttributes] =
    Show.show {
      case ENG1L => "ENG1L"
      case ENG2L => "ENG2L"
    }

  def genVehicleAttributes: Gen[VehicleAttributes] =
    Gen.oneOf(ENG1L, ENG2L)

  // Check digit (1 character).
  sealed trait CheckDigit

  final case object D9 extends CheckDigit

  implicit val showCheckDigit: Show[CheckDigit] =
    Show.show {
      case D9 => "9"
    }

  // Model year (1 character).
  sealed trait ModelYear

  final case object Y2000 extends ModelYear
  final case object Y2001 extends ModelYear
  final case object Y2002 extends ModelYear

  implicit val showModelYear: Show[ModelYear] =
    Show.show {
      case Y2000 => "Y"
      case Y2001 => "1"
      case Y2002 => "2"
    }

  def genModelYear: Gen[ModelYear] =
    Gen.oneOf(Y2000, Y2001, Y2002)

  // Plant code (1 character).
  sealed trait PlantCode

  final case object Plant1 extends PlantCode
  final case object Plant2 extends PlantCode

  implicit val showPlantCode: Show[PlantCode] =
    Show.show {
      case Plant1 => "A"
      case Plant2 => "B"
    }

  def genPlantCode: Gen[PlantCode] =
    Gen.oneOf(Plant1, Plant2)

  // Unique identifier (6 characters).
  type SequentialNumber = Int

}
