/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.security.Security
import java.time.Instant

import cats.data.NonEmptyList
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.data.EcuIdentifier.validatedEcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, RsaKeyType, TargetFilename, TufKey, ValidHardwareIdentifier, ValidTargetFilename}
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CreateDevice, CreateEcu, Ecu, SoftwareImage}
import com.advancedtelematic.ota.deviceregistry.data.Namespaces.defaultNs
import eu.timepit.refined.api.Refined
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.scalacheck.{Arbitrary, Gen}

trait DeviceGenerators {

  import Arbitrary._
  import Device._

  Security.addProvider(new BouncyCastleProvider())

  val genDeviceName: Gen[DeviceName] = for {
    //use a minimum length for DeviceName to reduce possibility of naming conflicts
    size <- Gen.choose(10, 100)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield Refined.unsafeApply(name.mkString)

  val genDeviceUUID: Gen[DeviceId] = Gen.delay(DeviceId.generate)

  val genDeviceId: Gen[DeviceOemId] = for {
    size <- Gen.choose(10, 100)
    name <- Gen.containerOfN[Seq, Char](size, Gen.alphaNumChar)
  } yield DeviceOemId(name.mkString)

  val genDeviceType: Gen[DeviceType] = for {
    t <- Gen.oneOf(DeviceType.values.toSeq)
  } yield t

  val genInstant: Gen[Instant] = for {
    millis <- Gen.chooseNum[Long](0, 10000000000000L)
  } yield Instant.ofEpochMilli(millis)

  val genEcuId: Gen[EcuIdentifier] =
    Gen.listOfN(64, Gen.alphaNumChar).map(_.mkString).map(validatedEcuIdentifier.from(_).right.get)

  val genEcuType: Gen[HardwareIdentifier] =
    Gen.chooseNum(10, 200).flatMap(Gen.listOfN(_, Gen.alphaNumChar)).map(_.mkString).map(Refined.unsafeApply[String, ValidHardwareIdentifier])

  val genFilePath: Gen[TargetFilename] =
    Gen.chooseNum(10, 200).flatMap(Gen.listOfN(_, Gen.alphaNumChar)).map(_.mkString).map(Refined.unsafeApply[String, ValidTargetFilename])

  val genTufKey: Gen[TufKey] = Gen.const(TufCrypto.generateKeyPair(RsaKeyType, 2048).pubkey)

  val genHexDigit: Gen[Char] = Gen.oneOf(('0' to '9') ++ ('a' to 'f'))

  val genSoftwareImage: Gen[SoftwareImage] = for {
    filepath <- genFilePath
    checksum <- Gen.listOfN(64, genHexDigit).map(_.mkString).map(Checksum(_).right.get)
    size <- Gen.posNum[Long]
  } yield SoftwareImage(filepath, checksum, size)

  val genDevice: Gen[Device] = genDeviceWith(genDeviceName, genDeviceId)

  val genCreateDevice: Gen[CreateDevice] = genCreateDeviceWith(genDeviceName, genDeviceId)

  def genDeviceWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceOemId]): Gen[Device] =
    for {
      uuid       <- genDeviceUUID
      name       <- deviceNameGen
      deviceId   <- deviceIdGen
      deviceType <- genDeviceType
      lastSeen   <- Gen.option(genInstant)
      activated  <- Gen.option(genInstant)
    } yield Device(defaultNs, uuid, name, deviceId, deviceType, lastSeen, Instant.now(), activated)

  def genEcu(deviceId: DeviceId, primary: Boolean): Gen[Ecu] =
    for {
      ecuId   <- genEcuId
      ecuType <- genEcuType
      tufKey  <- genTufKey
    } yield Ecu(deviceId, ecuId, ecuType, primary, tufKey)

  val genCreateEcu: Gen[CreateEcu] = for {
    ecuId <- genEcuId
    ecuType <- genEcuType
    clientKey <- genTufKey
  } yield CreateEcu(ecuId, ecuType, clientKey)

  def genCreateEcus(n: Int): Gen[NonEmptyList[CreateEcu]] =
    Gen.listOfN(n, genCreateEcu).map(NonEmptyList.fromListUnsafe)

  def genCreateDeviceWith(deviceNameGen: Gen[DeviceName], deviceIdGen: Gen[DeviceOemId], nEcus: Int = 1): Gen[CreateDevice] =
    for {
      uuid       <- Gen.option(genDeviceUUID)
      name       <- deviceNameGen
      deviceId   <- deviceIdGen
      deviceType <- genDeviceType
      createEcus <- genCreateEcus(nEcus)
    } yield CreateDevice(uuid, name, deviceId, createEcus.head.ecuId, createEcus, deviceType)

  def genCreateDeviceWithNEcus(n: Int): Gen[CreateDevice] = genCreateDeviceWith(genDeviceName, genDeviceId, n)

  def genConflictFreeCreateDevices(): Gen[Seq[CreateDevice]] =
    genConflictFreeCreateDevices(arbitrary[Int].sample.get)

  def genConflictFreeCreateDevices(n: Int): Gen[Seq[CreateDevice]] =
    for {
      dns  <- Gen.containerOfN[Seq, DeviceName](n, genDeviceName)
      dids <- Gen.containerOfN[Seq, DeviceOemId](n, genDeviceId)
    } yield {
      dns.zip(dids).map {
        case (nameG, idG) =>
          genCreateDeviceWith(nameG, idG).sample.get
      }
    }

  implicit lazy val arbDeviceName: Arbitrary[DeviceName] = Arbitrary(genDeviceName)
  implicit lazy val arbDeviceUUID: Arbitrary[DeviceId] = Arbitrary(genDeviceUUID)
  implicit lazy val arbDeviceId: Arbitrary[DeviceOemId]     = Arbitrary(genDeviceId)
  implicit lazy val arbDeviceType: Arbitrary[DeviceType] = Arbitrary(genDeviceType)
  implicit lazy val arbLastSeen: Arbitrary[Instant]      = Arbitrary(genInstant)
  implicit lazy val arbDevice: Arbitrary[Device]         = Arbitrary(genDevice)
  implicit lazy val arbCreateDevice: Arbitrary[CreateDevice]       = Arbitrary(genCreateDevice)

}

object DeviceGenerators extends DeviceGenerators

object InvalidDeviceGenerators extends DeviceGenerators with DeviceIdGenerators {
  val genInvalidVehicle: Gen[Device] = for {
    // TODO: for now, just generate an invalid VIN with a valid namespace
    deviceId <- genInvalidDeviceId
    d        <- genDevice
  } yield d.copy(deviceId = deviceId, namespace = defaultNs)

  def getInvalidVehicle: Device = genInvalidVehicle.sample.getOrElse(getInvalidVehicle)
}
