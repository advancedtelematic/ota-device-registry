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

import org.scalacheck.Gen

trait InvalidIdentGenerators {

  val EMPTY_STR = ""

  val genSymbol: Gen[Char] =
    Gen.oneOf('!', '@', '#', '$', '^', '&', '*', '(', ')')

  val genInvalidIdent: Gen[String] =
    for (prefix <- Gen.identifier;
         suffix <- Gen.identifier) yield prefix + getSymbol + suffix

  def getInvalidIdent: String = genInvalidIdent.sample.getOrElse(getInvalidIdent)

  def getSymbol: Char = genSymbol.sample.getOrElse(getSymbol)

}

object InvalidIdentGenerators extends InvalidIdentGenerators
