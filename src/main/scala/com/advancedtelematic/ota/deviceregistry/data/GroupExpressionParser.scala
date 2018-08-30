package com.advancedtelematic.ota.deviceregistry.data

import atto.Atto._
import atto._
import cats.data.NonEmptyList
import cats.syntax.either._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupExpression
import com.advancedtelematic.ota.deviceregistry.data.GroupExpressionAST._
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.DeviceTable
import slick.jdbc.MySQLProfile.api._
import slick.lifted.Rep

object GroupExpressionAST {
  sealed trait Expression
  case class DeviceIdContains(word: String) extends Expression
  case class DeviceIdCharAt(char: Char, position: Int) extends Expression
  case class Or(cond: NonEmptyList[Expression])  extends Expression
  case class And(cond: NonEmptyList[Expression]) extends Expression

  def compileToSlick(groupExpression: GroupExpression): DeviceTable => Rep[Boolean] =
    compileString(groupExpression.value, eval)

  def compileToScala(groupExpression: GroupExpression): Device => Boolean =
    compileString(groupExpression.value, evalToScala)

  private def compileString[T](str: String, fn: Expression => T): T =
    GroupExpressionParser.parse(str)
      .map(fn).right.get

  private def evalToScala(exp: Expression): Device => Boolean = exp match {
    case DeviceIdContains(word) =>
      (d: Device) => d.deviceId.exists(_.underlying.contains(word))

    case DeviceIdCharAt(p, c) =>
      (d: Device) => d.deviceId.exists(id => p < id.underlying.length && id.underlying.charAt(p) == c)

    case Or(conds) =>
      val evaledConds = conds.map(evalToScala)
      evaledConds.reduceLeft { (a, b) => (d: Device) => a(d) || b(d) }

    case And(conds) =>
      val evaledConds = conds.map(evalToScala)
      evaledConds.reduceLeft { (a, b) => (d: Device) => a(d) && b(d) }
  }

  def eval(exp: Expression): DeviceTable => Rep[Boolean] = exp match {
    case DeviceIdContains(word) =>
      (d: DeviceTable) => d.deviceId.mappedTo[String].like("%" + word + "%")

    case DeviceIdCharAt(c, p) =>
      (d: DeviceTable) => d.deviceId.mappedTo[String].substring(p, p + 1) === c.toString

    case Or(conds) =>
      val evaledConds = conds.map(eval)
      evaledConds.reduceLeft { (a, b) => (d: DeviceTable) => a(d) || b(d)
      }

    case And(conds) =>
      val evaledConds = conds.map(eval)
      evaledConds.reduceLeft { (a, b) => (d: DeviceTable) => a(d) && b(d)
      }
  }
}

object GroupExpressionParser {
  def parse(str: String) =
    parser.parse(str).done.either.leftMap(Errors.InvalidGroupExpression)

  private lazy val expression: Parser[Expression] = or | and | leftExpression

  private lazy val leftExpression: Parser[Expression] = deviceIdExpression | brackets

  private lazy val deviceIdExpression: Parser[Expression] = deviceIdCons ~> (deviceIdContains |  deviceIdCharAt)

  private lazy val brackets: Parser[Expression] = parens(expression)

  private lazy val parser: Parser[Expression] = expression <~ endOfInput

  private lazy val deviceIdCons: Parser[Unit] = token(string("deviceid")).map(_ => ())

  private lazy val deviceIdContains: Parser[Expression] = for {
    _ <- token(string("contains"))
    str <- takeWhile1(c => c.isLetterOrDigit || c == '-')
  } yield DeviceIdContains(str)

  private lazy val deviceIdCharAt: Parser[Expression] = for {
    _ <- string("position")
    pos <- parens(int.filter(_ > 0))
    _ <- skipWhitespace
    _ <- token(string("is"))
    char <- letterOrDigit
  } yield DeviceIdCharAt(char, pos - 1)

  private lazy val and: Parser[Expression] = for {
    a <- leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("and")) ~> token(leftExpression))
  } yield And(a :: b)

  private lazy val or: Parser[Expression] = for {
    a <- and | leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("or")) ~> token(and | leftExpression))
  } yield Or(a :: b)
}
