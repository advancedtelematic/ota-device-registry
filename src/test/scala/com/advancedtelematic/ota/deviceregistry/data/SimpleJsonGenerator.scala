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

import io.circe.{Json, JsonObject}
import org.scalacheck.{Arbitrary, Gen}

trait SimpleJsonGenerator {

  import Arbitrary._

  val simpleJsonPairGen: Gen[(String, Json)] = for {
    k <- Gen.identifier
    v <- arbitrary[String]
  } yield (k, Json.fromString(v))

  val simpleJsonGen: Gen[Json] = for {
    vs <- Gen.nonEmptyContainerOf[List, (String, Json)](simpleJsonPairGen)
  } yield Json.fromJsonObject(JsonObject.fromMap(vs.toMap))

  implicit lazy val arbSimpleJson: Arbitrary[Json] = Arbitrary(simpleJsonGen)
}

object SimpleJsonGenerator extends SimpleJsonGenerator
