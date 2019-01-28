package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import com.advancedtelematic.ota.deviceregistry.messages.DeleteDeviceRequest
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.test.DatabaseSpec
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

final class DeleteDeviceHandlerSpec
    extends FunSuite
    with Matchers
    with ScalaFutures
    with DatabaseSpec {

  import GeneratorOps._

  import scala.concurrent.ExecutionContext.Implicits.global
  val handler = new DeleteDeviceHandler()

  test("OTA-2445: do not fail when deleting non-existent device") {
    val msg = DeleteDeviceRequest(Gen.identifier.map(Namespace(_)).generate, Gen.uuid.map(DeviceId(_)).generate)
    handler(msg).futureValue shouldBe Done
  }
}
