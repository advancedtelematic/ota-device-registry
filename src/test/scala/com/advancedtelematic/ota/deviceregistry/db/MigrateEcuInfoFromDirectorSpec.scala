package com.advancedtelematic.ota.deviceregistry.db

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.advancedtelematic.libats.test.{DatabaseSpec, LongTest}
import com.advancedtelematic.ota.deviceregistry.data.DataType.Ecu
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import com.advancedtelematic.ota.deviceregistry.data.{DeviceGenerators, Namespaces}
import com.advancedtelematic.ota.deviceregistry.util.FakeDirectorClient
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{EitherValues, FunSuite, Matchers}

class MigrateEcuInfoFromDirectorSpec
    extends FunSuite
    with DeviceGenerators
    with ScalaFutures
    with DatabaseSpec
    with Matchers
    with EitherValues
    with Namespaces
    with Eventually
    with LongTest {

  implicit val system: ActorSystem                     = ActorSystem(this.getClass.getSimpleName)
  implicit val materializer: ActorMaterializer         = ActorMaterializer()
  import system.dispatcher

  test("should store the ECUs from director") {
    val newDevice = genCreateDeviceWithNEcus(20).generate
    val deviceId  = db.run(DeviceRepository.create(defaultNs, newDevice)).futureValue

    val ecusHead   = newDevice.ecus.head
    val primaryEcu = Ecu(deviceId, ecusHead.ecuId, ecusHead.ecuType, primary = true, ecusHead.clientKey)
    val secondaryEcus =
      newDevice.ecus.tail.map(e => Ecu(deviceId, e.ecuId, e.ecuType, primary = false, e.clientKey))
    val allEcus = primaryEcu +: secondaryEcus

    val director = new FakeDirectorClient
    director.addEcus(defaultNs, deviceId, allEcus: _*)

    val migrator = new MigrateEcuInfoFromDirector(director)
    migrator.run.futureValue

    eventually {
      val result = db
        .run(EcuRepository.listEcusForDevice(deviceId, None, None))
        .map(_.values)
        .futureValue
      result should contain theSameElementsAs allEcus
    }
  }

  test("the migration is idempotent") {
    val newDevice = genCreateDeviceWithNEcus(1).generate
    val deviceId  = db.run(DeviceRepository.create(defaultNs, newDevice)).futureValue
    val ecusHead  = newDevice.ecus.head
    val ecu       = Ecu(deviceId, ecusHead.ecuId, ecusHead.ecuType, primary = true, ecusHead.clientKey)

    val director = new FakeDirectorClient
    val migrator = new MigrateEcuInfoFromDirector(director)

    migrator.saveEcu(ecu).futureValue
    val foundEcu1 = db.run(EcuRepository.listEcusForDevice(deviceId, None, None)).futureValue.values
    foundEcu1 should contain only ecu

    // Repeat to make sure the method is idempotent when given the same value
    migrator.saveEcu(ecu).futureValue
    val foundEcu2 = db.run(EcuRepository.listEcusForDevice(deviceId, None, None)).futureValue.values
    foundEcu2 should contain only ecu

  }

}
