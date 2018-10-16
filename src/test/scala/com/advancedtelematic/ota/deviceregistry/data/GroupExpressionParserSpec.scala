package com.advancedtelematic.ota.deviceregistry.data

import cats.data.NonEmptyList
import cats.implicits._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.Errors
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.GroupExpressionAST.{And, DeviceIdCharAt, DeviceIdContains, Or}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.DeviceTable
import org.scalatest.EitherValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FunSuite, Matchers}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.Future

class GroupExpressionParserSpec extends FunSuite with Matchers {

  def runParserUnchecked(str: String) = GroupExpressionParser.parse(str)

  def runParser(str: String) =
    runParserUnchecked(str).valueOr(err => throw new RuntimeException(err))

  test("parses deviceid contains") {
    runParser("deviceid contains something") shouldBe DeviceIdContains("something")
  }

  test("parses deviceid parentheses") {
    runParser("(deviceid contains something)") shouldBe DeviceIdContains("something")
  }

  test("parses or expression") {
    runParser("deviceid contains something0 or deviceid contains other0") shouldBe Or(
      NonEmptyList.of(DeviceIdContains("something0"), DeviceIdContains("other0"))
    )
  }

  test("parses multiple or expressions with parens") {
    runParser("(deviceid contains bananas1) or deviceid contains oranges or deviceid contains melons") shouldBe
    Or(NonEmptyList.of(DeviceIdContains("bananas1"), DeviceIdContains("oranges"), DeviceIdContains("melons")))
  }

  test("parses multiple or expressions with nested parens") {
    runParser("(deviceid contains bananas) or ((deviceid contains oranges) or deviceid contains melons)") shouldBe
    Or(
      NonEmptyList.of(DeviceIdContains("bananas"),
                      Or(NonEmptyList.of(DeviceIdContains("oranges"), DeviceIdContains("melons"))))
    )
  }

  test("parses multiple or expressions") {
    runParser("deviceid contains something0 or deviceid contains other0 or deviceid contains other1") shouldBe
    Or(NonEmptyList.of(DeviceIdContains("something0"), DeviceIdContains("other0"), DeviceIdContains("other1")))
  }

  test("parses and expression") {
    runParser("(deviceid contains something0) and (deviceid contains other0)") shouldBe
    And(NonEmptyList.of(DeviceIdContains("something0"), DeviceIdContains("other0")))
  }

  test("parses and/or expression") {
    runParser("deviceid contains bananas and (deviceid contains oranges or deviceid contains melons)")
    And(
      NonEmptyList.of(DeviceIdContains("bananas"),
                      Or(NonEmptyList.of(DeviceIdContains("oranges"), DeviceIdContains("melons"))))
    )
  }

  test("parses and/or expression without parens") {
    runParser("deviceid contains bananas and deviceid contains oranges or deviceid contains melons") shouldBe
    Or(
      NonEmptyList.of(And(NonEmptyList.of(DeviceIdContains("bananas"), DeviceIdContains("oranges"))),
                      DeviceIdContains("melons"))
    )
  }

  test("parses nested expressions when using parens") {
    runParser("(deviceid contains something0) and ((deviceid contains other0) or (deviceid contains melons))") shouldBe
    And(
      NonEmptyList.of(DeviceIdContains("something0"),
                      Or(NonEmptyList.of(DeviceIdContains("other0"), DeviceIdContains("melons"))))
    )
  }

  test("parses -") {
    runParser("deviceid contains eo7z-Onogw") shouldBe DeviceIdContains("eo7z-Onogw")
  }

  test("parses boolean expressions without parenthesis") {
    runParser("deviceid contains eo7zOnogw or deviceid contains Ku05MCxEE6GQ2iKh and deviceid contains ySqlJlu") shouldBe
      Or(NonEmptyList.of(
        DeviceIdContains("eo7zOnogw"),
        And(
          NonEmptyList.of(DeviceIdContains("Ku05MCxEE6GQ2iKh"), DeviceIdContains("ySqlJlu"))
        )
      ))
  }

  test("parses 'deviceid position is'") {
    runParser("deviceid position(2) is a") shouldBe DeviceIdCharAt('a', 1)
  }

  test("parses 'deviceid position is' number as char") {
    runParser("deviceid position(2) is 8") shouldBe DeviceIdCharAt('8', 1)
  }

  test("parses 'deviceid position is' with parenthesis") {
    runParser("(deviceid position(2) is 8)") shouldBe DeviceIdCharAt('8', 1)
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
    Or(NonEmptyList.of(DeviceIdCharAt('a', 0), DeviceIdCharAt('8', 1)))
  }

  test("parses 'deviceid position is' with multiple or expressions") {
    runParser("deviceid position(1) is a or (deviceid position(2) is 8 or deviceid position(3) is A)") shouldBe
    Or(NonEmptyList.of(DeviceIdCharAt('a', 0), Or(NonEmptyList.of(DeviceIdCharAt('8', 1), DeviceIdCharAt('A', 2)))))
  }

  test("parses 'deviceid position is' with and expression") {
    runParser("deviceid position(1) is a and deviceid position(2) is 8") shouldBe
    And(NonEmptyList.of(DeviceIdCharAt('a', 0), DeviceIdCharAt('8', 1)))
  }

  test("parses 'deviceid position is' with multiple and expressions") {
    runParser("deviceid position(1) is a and (deviceid position(2) is 8 and deviceid position(3) is A)") shouldBe
    And(NonEmptyList.of(DeviceIdCharAt('a', 0), And(NonEmptyList.of(DeviceIdCharAt('8', 1), DeviceIdCharAt('A', 2)))))
  }

  test("parses 'deviceid contains' or 'deviceid position is'") {
    runParser("deviceid contains something0 or deviceid position(3) is x") shouldBe
    Or(NonEmptyList.of(DeviceIdContains("something0"), DeviceIdCharAt('x', 2)))
  }

  test("parses 'deviceid position is' or nested 'deviceid contains'") {
    runParser("deviceid position(3) is x or (deviceid contains something0 and deviceid contains something0else)") shouldBe
    Or(
      NonEmptyList.of(DeviceIdCharAt('x', 2),
                      And(NonEmptyList.of(DeviceIdContains("something0"), DeviceIdContains("something0else"))))
    )
  }

  test("parses 'deviceid position is' and 'deviceid contains'") {
    runParser("deviceid position(3) is x and deviceid contains something0") shouldBe
    And(NonEmptyList.of(DeviceIdCharAt('x', 2), DeviceIdContains("something0")))
  }

  test("parses 'deviceid contains' and nested 'deviceid position is'") {
    runParser("deviceid contains something0 and (deviceid position(1) is x or deviceid position(2) is y)") shouldBe
    And(
      NonEmptyList.of(DeviceIdContains("something0"), Or(NonEmptyList.of(DeviceIdCharAt('x', 0), DeviceIdCharAt('y', 1))))
    )
  }

}

class GroupExpressionRunSpec extends FunSuite with Matchers with DatabaseSpec with ScalaFutures {

  val ns = Namespace("group-exp")

  import scala.concurrent.ExecutionContext.Implicits.global
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(300, Millis), interval = Span(30, Millis))

  val device0 =
    DeviceGenerators.genDeviceT
      .sample
      .get
      .copy(oemId = DeviceOemId("deviceABC"))
  val device1 =
    DeviceGenerators.genDeviceT
      .sample
      .get
      .copy(oemId = DeviceOemId("deviceDEF"))

  override def beforeAll(): Unit = {
    super.beforeAll()

    val io = List(
      DeviceRepository.create(ns, device0),
      DeviceRepository.create(ns, device1)
    )

    db.run(DBIO.sequence(io)).futureValue
  }

  def runQuery(expression: DeviceTable => Rep[Boolean]): Future[Seq[DeviceOemId]] = db.run {
    DeviceRepository.devices
      .filter(expression)
      .filter(_.namespace === ns)
      .map(_.oemId)
      .result
  }

  def runGroupExpression(strExp: String): Seq[DeviceOemId] = {
    val f = GroupExpressionParser.parse(strExp).right.get
    val gexp = GroupExpressionAST.eval(f)
    runQuery(gexp).futureValue
  }

  test("returns matching device") {
    runGroupExpression(s"deviceid contains ABC") shouldBe Seq(device0.oemId)
  }

  test("returns matching devices with or") {
    val res = runGroupExpression(s"(deviceid contains A) or (deviceid contains D)")
    res should contain(device0.oemId)
    res should contain(device1.oemId)
  }

  test("does not match devices that do not contain value") {
    runGroupExpression(s"deviceid contains Z") should be(empty)
  }

  test("matches both expressions when using and") {
    runGroupExpression(s"(deviceid contains A) and (deviceid contains C)") shouldBe Seq(device0.oemId)
    runGroupExpression(s"(deviceid contains A) and (deviceid contains D)") should be(empty)
  }

  test("matches all expressions when using and") {
    runGroupExpression(s"((deviceid contains A) and (deviceid contains B)) and (deviceid contains C)") shouldBe Seq(
      device0.oemId
    )
  }

  test("matches all expressions when using and without parens") {
    runGroupExpression(s"deviceid contains A and deviceid contains B and (deviceid contains C)") shouldBe Seq(
      device0.oemId
    )
  }

  test("returns matching 'deviceid position is'") {
    runGroupExpression(s"deviceid position(1) is d") shouldBe Seq(device0.oemId, device1.oemId)
    runGroupExpression(s"deviceid position(8) is B") shouldBe Seq(device0.oemId)
  }

  test("returns matching 'deviceid position is not'") {
    runGroupExpression(s"deviceid position(1) is not x") shouldBe Seq(device0.oemId, device1.oemId)
    runGroupExpression(s"deviceid position(8) is not B") shouldBe Seq(device1.oemId)
  }

  test("'deviceid position is' and 'deviceid position is not' cancel out") {
    runGroupExpression(s"deviceid position(1) is d and deviceid position(1) is not d") shouldBe empty
  }

  test("'deviceid position is' or 'deviceid position is not' behaves as a tautology") {
    runGroupExpression(s"deviceid position(8) is B or deviceid position(8) is not B") shouldBe Seq(device0.oemId, device1.oemId)
  }

  test("returns matching 'deviceid position is' is case-insensitive") {
    runGroupExpression(s"deviceid position(1) is D") shouldBe Seq(device0.oemId, device1.oemId)
    runGroupExpression(s"deviceid position(8) is b") shouldBe Seq(device0.oemId)
  }

  test("returns matching 'deviceid position is not' is case-insensitive") {
    runGroupExpression(s"deviceid position(1) is not X") shouldBe Seq(device0.oemId, device1.oemId)
    runGroupExpression(s"deviceid position(8) is not b") shouldBe Seq(device1.oemId)
  }

  test("does not match 'deviceid position is' when different char at position") {
    runGroupExpression(s"deviceid position(8) is Z") shouldBe empty
  }

  test("does not match 'deviceid position is not' when that char at position") {
    runGroupExpression(s"deviceid position(1) is not d") shouldBe empty
  }

  test("does not match 'deviceid position is' when position is out of bounds") {
    runGroupExpression(s"deviceid position(9) is X") shouldBe empty
    runGroupExpression(s"deviceid position(1000) is Y") shouldBe empty
  }

  test("matches all 'deviceid position is not' when position is out of bounds") {
    runGroupExpression(s"deviceid position(9) is not X") shouldBe Seq(device0.oemId, device1.oemId)
    runGroupExpression(s"deviceid position(1000) is not Y") shouldBe Seq(device0.oemId, device1.oemId)
  }

  test("returns matching 'deviceid position is' with or") {
    runGroupExpression(s"deviceid position(7) is E or deviceid position(8) is E") shouldBe Seq(device1.oemId)
  }

  test("returns matching 'deviceid position is' with and") {
    runGroupExpression(s"deviceid position(7) is D and deviceid position(8) is E") shouldBe Seq(device1.oemId)
  }

  test("returns matching 'deviceid position is not' with and") {
    runGroupExpression(s"deviceid position(7) is not D and deviceid position(8) is not E") shouldBe Seq(device0.oemId)
  }

  test("returns matching 'deviceid contains' or 'deviceid position is' or 'deviceid position is not' when either condition is true") {
    runGroupExpression(s"deviceid contains evic or deviceid position(9) is Z or deviceid position(9) is not X") shouldBe Seq(device0.oemId, device1.oemId)
    runGroupExpression(s"deviceid contains nope or deviceid position(9) is C or deviceid position(9) is not F") shouldBe Seq(device0.oemId)
    runGroupExpression(s"deviceid contains nope or deviceid position(9) is Z or deviceid position(9) is not C") shouldBe Seq(device1.oemId)
  }

  test("does not match 'deviceid contains' or 'deviceid position is' or 'deviceid position is not' when all conditions are false") {
    runGroupExpression(s"deviceid contains nope or deviceid position(9) is Z or deviceid position(1) is not D") shouldBe empty
  }

  test("returns matching 'deviceid contains' and 'deviceid position is' and 'deviceid position is not' when all conditions are true") {
    runGroupExpression(s"deviceid contains evic and deviceid position(9) is C and deviceid position(8) is not C") shouldBe Seq(device0.oemId)
  }

  test("does not match 'deviceid contains' and 'deviceid position is' and 'deviceid position is not' when either condition is false") {
    runGroupExpression(s"deviceid contains nope and deviceid position(9) is C and deviceid position(8) is not C") shouldBe empty
    runGroupExpression(s"deviceid contains evic and deviceid position(9) is Z and deviceid position(8) is not C") shouldBe empty
    runGroupExpression(s"deviceid contains evic and deviceid position(9) is C and deviceid position(8) is not B") shouldBe empty
  }

}
