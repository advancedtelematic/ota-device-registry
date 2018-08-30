package com.advancedtelematic.ota.deviceregistry.data

import cats.data.NonEmptyList
import cats.implicits._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.GroupExpressionAST.{And, DeviceIdCharAt, DeviceContains, Or}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.DeviceTable
import org.scalatest.EitherValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FunSuite, Matchers}
import slick.jdbc.MySQLProfile.api._

class GroupExpressionParserSpec extends FunSuite with Matchers {

  def runParserUnchecked(str: String) = GroupExpressionParser.parse(str)

  def runParser(str: String) =
    runParserUnchecked(str).valueOr(err => throw new RuntimeException(err))

  test("parses deviceid contains") {
    runParser("deviceid contains something") shouldBe DeviceContains("something")
  }

  test("parses deviceid parentheses") {
    runParser("(deviceid contains something)") shouldBe DeviceContains("something")
  }

  test("parses or expression") {
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
    Or(
      NonEmptyList.of(DeviceContains("bananas"),
                      Or(NonEmptyList.of(DeviceContains("oranges"), DeviceContains("melons"))))
    )
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
    And(
      NonEmptyList.of(DeviceContains("bananas"),
                      Or(NonEmptyList.of(DeviceContains("oranges"), DeviceContains("melons"))))
    )
  }

  test("parses and/or expression without parens") {
    runParser("deviceid contains bananas and deviceid contains oranges or deviceid contains melons") shouldBe
    Or(
      NonEmptyList.of(And(NonEmptyList.of(DeviceContains("bananas"), DeviceContains("oranges"))),
                      DeviceContains("melons"))
    )
  }

  test("parses nested expressions when using parens") {
    runParser("(deviceid contains something0) and ((deviceid contains other0) or (deviceid contains melons))") shouldBe
    And(
      NonEmptyList.of(DeviceContains("something0"),
                      Or(NonEmptyList.of(DeviceContains("other0"), DeviceContains("melons"))))
    )
  }

  test("parses -") {
    runParser("deviceid contains eo7z-Onogw") shouldBe DeviceContains("eo7z-Onogw")
  }

  test("parses boolean expressions without parenthesis") {
    runParser("deviceid contains eo7zOnogw or deviceid contains Ku05MCxEE6GQ2iKh and deviceid contains ySqlJlu") shouldBe
      Or(NonEmptyList.of(
        DeviceContains("eo7zOnogw"),
        And(
          NonEmptyList.of(DeviceContains("Ku05MCxEE6GQ2iKh"), DeviceContains("ySqlJlu"))
        )
      ))
  }

  test("parses 'deviceid position is'") {
    runParser("deviceid position(2) is a") shouldBe DeviceIdCharAt(1, 'a')
  }

  test("parses 'deviceid position is' is case-sensitive") {
    runParser("deviceid position(2) is A") shouldBe DeviceIdCharAt(1, 'A')
  }

  test("parses 'deviceid position is' number as char") {
    runParser("deviceid position(2) is 8") shouldBe DeviceIdCharAt(1, '8')
  }

  test("parses 'deviceid position is' ignores chars after first") {
    pending
    runParser("deviceid position(2) is abc") shouldBe DeviceIdCharAt(1, 'a')
  }

  test("parses 'deviceid position is' with parenthesis") {
    runParser("(deviceid position(2) is 8)") shouldBe DeviceIdCharAt(1, '8')
  }

  test("fails to parse 'deviceid position is' when the position is not positive") {
    runParserUnchecked("deviceid position(0) is a").left.value shouldBe a[Errors.RawError]
    runParserUnchecked("deviceid position(-1) is a").left.value shouldBe a[Errors.RawError]
  }

  test("fails to parse 'deviceid position is' when the char is not alphanumeric") {
    runParserUnchecked("deviceid position(1) is -").left.value shouldBe a[Errors.RawError]
    runParserUnchecked("deviceid position(1) is %").left.value shouldBe a[Errors.RawError]
    runParserUnchecked("deviceid position(1) is }").left.value shouldBe a[Errors.RawError]
  }

  test("fails to parse 'deviceid position is' when more than one char is given") {
    runParserUnchecked("deviceid position(1) is abc").left.value shouldBe a[Errors.RawError]
  }

  test("parses 'deviceid position is' with or expression") {
    runParser("deviceid position(1) is a or deviceid position(2) is 8") shouldBe
    Or(NonEmptyList.of(DeviceIdCharAt(0, 'a'), DeviceIdCharAt(1, '8')))
  }

  test("parses 'deviceid position is' with multiple or expressions") {
    runParser("deviceid position(1) is a or (deviceid position(2) is 8 or deviceid position(3) is A)") shouldBe
    Or(NonEmptyList.of(DeviceIdCharAt(0, 'a'), Or(NonEmptyList.of(DeviceIdCharAt(1, '8'), DeviceIdCharAt(2, 'A')))))
  }

  test("parses 'deviceid position is' with and expression") {
    runParser("deviceid position(1) is a and deviceid position(2) is 8") shouldBe
    And(NonEmptyList.of(DeviceIdCharAt(0, 'a'), DeviceIdCharAt(1, '8')))
  }

  test("parses 'deviceid position is' with multiple and expressions") {
    runParser("deviceid position(1) is a and (deviceid position(2) is 8 and deviceid position(3) is A)") shouldBe
    And(NonEmptyList.of(DeviceIdCharAt(0, 'a'), And(NonEmptyList.of(DeviceIdCharAt(1, '8'), DeviceIdCharAt(2, 'A')))))
  }

  test("parses 'deviceid contains' or 'deviceid position is'") {
    runParser("deviceid contains something0 or deviceid position(3) is x") shouldBe
    Or(NonEmptyList.of(DeviceContains("something0"), DeviceIdCharAt(2, 'x')))
  }

  test("parses 'deviceid position is' or nested 'deviceid contains'") {
    runParser("deviceid position(3) is x or (deviceid contains something0 and deviceid contains something0else)") shouldBe
    Or(
      NonEmptyList.of(DeviceIdCharAt(2, 'x'),
                      And(NonEmptyList.of(DeviceContains("something0"), DeviceContains("something0else"))))
    )
  }

  test("parses 'deviceid position is' and 'deviceid contains'") {
    runParser("deviceid position(3) is x and deviceid contains something0") shouldBe
    And(NonEmptyList.of(DeviceIdCharAt(2, 'x'), DeviceContains("something0")))
  }

  test("parses 'deviceid contains' and nested 'deviceid position is'") {
    runParser("deviceid contains something0 and (deviceid position(1) is x or deviceid position(2) is y)") shouldBe
    And(
      NonEmptyList.of(DeviceContains("something0"), Or(NonEmptyList.of(DeviceIdCharAt(0, 'x'), DeviceIdCharAt(1, 'y'))))
    )
  }

}

class GroupExpressionRunSpec extends FunSuite with Matchers with DatabaseSpec with ScalaFutures {

  val ns = Namespace("group-exp")

  import scala.concurrent.ExecutionContext.Implicits.global
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(300, Millis), interval = Span(30, Millis))

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
    val f = GroupExpressionParser.parse(strExp).right.get
    val gexp = GroupExpressionAST.eval(f)
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
    runGroupExpression(s"((deviceid contains A) and (deviceid contains B)) and (deviceid contains C)") shouldBe Seq(
      device0.deviceUuid.get
    )
  }

  test("matches all expressions when using and without parens") {
    runGroupExpression(s"deviceid contains A and deviceid contains B and (deviceid contains C)") shouldBe Seq(
      device0.deviceUuid.get
    )
  }

  test("returns matching 'deviceid position is'") {
    runGroupExpression(s"deviceid position(1) is d") shouldBe Seq(device0.deviceUuid.get, device1.deviceUuid.get)
    runGroupExpression(s"deviceid position(8) is B") shouldBe Seq(device0.deviceUuid.get)
  }

  test("does not match 'deviceid position is' when different char at position") {
    runGroupExpression(s"deviceid position(8) is Z") shouldBe empty
  }

  test("does not match 'deviceid position is' when position is out of bounds") {
    val minMaxDeviceIdLength =
      scala.math.max(device0.deviceId.get.underlying.length, device1.deviceId.get.underlying.length)
    runGroupExpression(s"deviceid position($minMaxDeviceIdLength) is X") shouldBe empty
    runGroupExpression(s"deviceid position(1000) is Y") shouldBe empty
  }

  test("returns matching 'deviceid position is' with or") {
    runGroupExpression(s"deviceid position(7) is E or deviceid position(8) is E") shouldBe Seq(device1.deviceUuid.get)
  }

  test("returns matching 'deviceid position is' with and") {
    runGroupExpression(s"deviceid position(7) is D and deviceid position(8) is E") shouldBe Seq(device1.deviceUuid.get)
  }

  test("returns matching 'deviceid contains' or 'deviceid position is' when either condition is true") {
    runGroupExpression(s"deviceid contains evic or deviceid position(9) is Z") shouldBe Seq(device0.deviceUuid.get,
                                                                                            device1.deviceUuid.get)
    runGroupExpression(s"deviceid contains nope or deviceid position(9) is C") shouldBe Seq(device0.deviceUuid.get)
    runGroupExpression(s"deviceid position(9) is Z or deviceid contains evic") shouldBe Seq(device0.deviceUuid.get,
                                                                                            device1.deviceUuid.get)
    runGroupExpression(s"deviceid position(9) is C or deviceid contains nope") shouldBe Seq(device0.deviceUuid.get)
  }

  test("does not match 'deviceid contains' or 'deviceid position is' when both conditions are false") {
    runGroupExpression(s"deviceid contains nope or deviceid position(9) is Z") shouldBe empty
    runGroupExpression(s"deviceid position(9) is Z or deviceid contains nope") shouldBe empty
  }

  test("returns matching 'deviceid contains' and 'deviceid position is' when both conditions are true") {
    runGroupExpression(s"deviceid contains evic and deviceid position(9) is C") shouldBe Seq(device0.deviceUuid.get)
    runGroupExpression(s"deviceid position(9) is C and deviceid contains evic") shouldBe Seq(device0.deviceUuid.get)
  }

  test("does not match 'deviceid contains' and 'deviceid position is' when either condition is false") {
    runGroupExpression(s"deviceid contains evic and deviceid position(9) is Z") shouldBe empty
    runGroupExpression(s"deviceid contains nope and deviceid position(9) is C") shouldBe empty
    runGroupExpression(s"deviceid position(9) is Z and deviceid contains evic") shouldBe empty
    runGroupExpression(s"deviceid position(9) is C and deviceid contains nope") shouldBe empty
  }

}
