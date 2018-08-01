package com.advancedtelematic.ota.deviceregistry.data

import org.scalatest.{FunSuite, Matchers}
import atto._
import Atto._
import cats.data.NonEmptyList
import cats.implicits._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.GroupExpAST.{And, DeviceContains, Or}
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceId
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.DeviceTable
import org.scalatest.concurrent.ScalaFutures
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._

class GroupExpressionParserSpec extends FunSuite with Matchers {

  def runParser(str: String) =
    GroupExpressionParser.parser.parse(str).done.either.valueOr(err => throw new RuntimeException(err))

  test("parses deviceid contains") {
    runParser("deviceid contains something") shouldBe DeviceContains("something")
  }

  test("parses deviceid parentheses") {
    runParser("(deviceid contains something)") shouldBe DeviceContains("something")
  }

  test("parses or expr0ession") {
    runParser("deviceid contains something0 or deviceid contains other0") shouldBe Or(
      NonEmptyList.of(DeviceContains("something0"), DeviceContains("other0"))
    )
  }

  test("parses multiple or expressions with parens") {
    runParser("(deviceid contains bananas1) or deviceid contains oranges or deviceid contains melons") shouldBe
      Or(NonEmptyList.of(DeviceContains("bananas1"), DeviceContains("oranges"), DeviceContains("melons")))
  }

  test("parses multiple or expressions with nested parens") {
    runParser("(deviceid contains bananas) or ((deviceid contains oranges) or deviceid contains melons)") shouldBe
    Or(NonEmptyList.of(DeviceContains("bananas"), Or(NonEmptyList.of(DeviceContains("oranges"), DeviceContains("melons")))))
  }

  test("parses multiple or expressions") {
    runParser("deviceid contains something0 or deviceid contains other0 or deviceid contains other1") shouldBe
      Or(NonEmptyList.of(DeviceContains("something0"), DeviceContains("other0"), DeviceContains("other1")))
  }

  test("parses and expression") {
    runParser("(deviceid contains something0) and (deviceid contains other0)") shouldBe
      And(NonEmptyList.of(DeviceContains("something0"), DeviceContains("other0")))
  }

  test("parses and/or expression") {
    runParser("deviceid contains bananas and (deviceid contains oranges or deviceid contains melons)")
    And(NonEmptyList.of(DeviceContains("bananas"), Or(NonEmptyList.of(DeviceContains("oranges"), DeviceContains("melons")))))
  }

  test("parses and/or expression without parens") {
    runParser("deviceid contains bananas and deviceid contains oranges or deviceid contains melons") shouldBe
      Or(NonEmptyList.of(And(NonEmptyList.of(DeviceContains("bananas"), DeviceContains("oranges"))), DeviceContains("melons")))
  }

  test("parses nested expressions when using parens") {
    runParser("(deviceid contains something0) and ((deviceid contains other0) or (deviceid contains melons))") shouldBe
      And(NonEmptyList.of(DeviceContains("something0"), Or(NonEmptyList.of(DeviceContains("other0"), DeviceContains("melons")))))
  }
}

class GroupExpressionRunSpec extends FunSuite with Matchers with DatabaseSpec with ScalaFutures {

  val ns = Namespace("group-exp")

  import scala.concurrent.ExecutionContext.Implicits.global

  val device0 =
    DeviceGenerators.genDeviceT
      .retryUntil(_.deviceUuid.isDefined)
      .sample
      .get
      .copy(deviceId = Some(DeviceId("deviceABC")))
  val device1 =
    DeviceGenerators.genDeviceT
      .retryUntil(_.deviceUuid.isDefined)
      .sample
      .get
      .copy(deviceId = Some(DeviceId("deviceDEF")))

  override def beforeAll(): Unit = {
    super.beforeAll()

    val io = List(
      DeviceRepository.create(ns, device0),
      DeviceRepository.create(ns, device1)
    )

    db.run(DBIO.sequence(io)).futureValue
  }

  def runQuery(expression: DeviceTable => Rep[Boolean]) = db.run {
    DeviceRepository.devices
      .filter(expression)
      .filter(_.namespace === ns)
      .map(_.uuid)
      .result
  }

  def runGroupExpression(strExp: String) = {
    val f = GroupExpressionParser.parser.parse(strExp).done.either.right.get
    println(s"evaluated: $f")
    val gexp = GroupExpAST.eval(f)
    runQuery(gexp).futureValue
  }

  test("returns matching device") {
    runGroupExpression(s"deviceid contains ABC") shouldBe Seq(device0.deviceUuid.get)
  }

  test("returns matching devices with or") {
    val res = runGroupExpression(s"(deviceid contains A) or (deviceid contains D)")
    res should contain(device0.deviceUuid.get)
    res should contain(device1.deviceUuid.get)
  }

  test("does not match devices that do not contain value") {
    runGroupExpression(s"deviceid contains Z") should be(empty)
  }

  test("matches both expressions when using and") {
    runGroupExpression(s"(deviceid contains A) and (deviceid contains C)") shouldBe Seq(device0.deviceUuid.get)
    runGroupExpression(s"(deviceid contains A) and (deviceid contains D)") should be(empty)
  }

  test("matches all expressions when using and") {
    runGroupExpression(s"((deviceid contains A) and (deviceid contains B)) and (deviceid contains C)") shouldBe Seq(device0.deviceUuid.get)
  }

  test("matches all expressions when using and without parens") {
    runGroupExpression(s"deviceid contains A and deviceid contains B and (deviceid contains C)") shouldBe Seq(device0.deviceUuid.get)
  }
}
