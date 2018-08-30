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
  case class DeviceContains(word: String) extends Expression
  case class DeviceIdCharAt(position: Int, char: Char) extends Expression
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
    case DeviceContains(word) =>
      (d: Device) =>
        d.deviceId.exists(_.underlying.contains(word))

    case DeviceIdCharAt(p, c) =>
      (d: Device) => d.deviceId.exists(id => p < id.underlying.length && id.underlying.charAt(p) == c)

    case Or(conds) =>
      val evaledConds = conds.map(evalToScala)

      evaledConds.reduceLeft { (a, b) => (d: Device) =>
        a(d) || b(d)
      }
    case And(conds) =>
      val evaledConds = conds.map(evalToScala)

      evaledConds.reduceLeft { (a, b) => (d: Device) =>
        a(d) && b(d)
      }
  }

  def eval(exp: Expression): DeviceTable => Rep[Boolean] = exp match {
    case DeviceContains(word) =>
      (d: DeviceTable) =>
        d.deviceId.mappedTo[String].like("%" + word + "%")

    case DeviceIdCharAt(p, c) =>
      (d: DeviceTable) => d.deviceId.mappedTo[String].substring(p, p + 1) === c.toString

    case Or(conds) =>
      val cc = conds.map(eval)
      cc.reduceLeft { (a, b) => (d: DeviceTable) =>
        a(d) || b(d)
      }
    case And(conds) =>
      val cc = conds.map(eval)

      cc.reduceLeft { (a, b) => (d: DeviceTable) =>
        a(d) && b(d)
      }
  }
}

object GroupExpressionParser {
  def parse(str: String) =
    parser.parse(str).done.either.leftMap(Errors.InvalidGroupExpression)

  private lazy val expression: Parser[Expression] = or | and | leftExpression

  private lazy val leftExpression: Parser[Expression] = deviceIdContains | deviceIdCharAt | brackets

  private lazy val brackets: Parser[Expression] = parens(expression)

  private lazy val parser: Parser[Expression] = expression <~ endOfInput

  private lazy val deviceIdContains: Parser[Expression] = for {
    _ <- string("deviceid")
    _ <- skipWhitespace
    _ <- string("contains")
    _ <- skipWhitespace
    b <- takeWhile1(c => c.isLetterOrDigit || c == '-')
  } yield DeviceContains(b)

  private lazy val deviceIdCharAt: Parser[Expression] = for {
    _ <- string("deviceid")
    _ <- skipWhitespace
    _ <- string("position")
    p <- parens(int.filter(_ > 0))
    _ <- skipWhitespace
    _ <- string("is")
    _ <- whitespace
    c <- letterOrDigit
  } yield DeviceIdCharAt(p - 1, c)

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
