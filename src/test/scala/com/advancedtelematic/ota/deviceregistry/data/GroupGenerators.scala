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

import java.time.Instant

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import org.scalacheck.{Arbitrary, Gen}

trait GroupGenerators {

  private lazy val defaultNs: Namespace = Namespace("default")

  def genGroupName(charGen: Gen[Char] = Arbitrary.arbChar.arbitrary): Gen[GroupName] = for {
    strLen <- Gen.choose(2, 100)
    name   <- Gen.listOfN[Char](strLen, charGen)
  } yield GroupName(name.mkString).right.get

  def genStaticGroup: Gen[Group] = for {
    groupName <- genGroupName()
    createdAt <- Gen.resize(1000000000, Gen.posNum[Long]).map(Instant.ofEpochSecond)
  } yield Group(GroupId.generate(), groupName, defaultNs, createdAt, GroupType.static, None)

  implicit lazy val arbGroupName: Arbitrary[GroupName] = Arbitrary(genGroupName())
  implicit lazy val arbStaticGroup: Arbitrary[Group] = Arbitrary(genStaticGroup)
}

object GroupGenerators extends GroupGenerators
