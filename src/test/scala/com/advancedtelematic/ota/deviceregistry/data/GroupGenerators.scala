/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.time.Instant

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.DeviceGenerators.genDeviceT
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

  val genGroupNameWithDeviceTs: Gen[(GroupName, List[DeviceT])] = for {
    groupName <- genGroupName()
    deviceTs <- Gen.resize(10, Gen.listOf(genDeviceT))
  } yield groupName -> deviceTs

  def genGroupNameWithDeviceTsMap(size: Int = 10): Gen[Map[GroupName, Seq[DeviceT]]] =
    Gen.resize(size, Gen.listOf(genGroupNameWithDeviceTs)).map(_.toMap)

  implicit lazy val arbGroupName: Arbitrary[GroupName] = Arbitrary(genGroupName())
  implicit lazy val arbStaticGroup: Arbitrary[Group] = Arbitrary(genStaticGroup)
}

object GroupGenerators extends GroupGenerators
