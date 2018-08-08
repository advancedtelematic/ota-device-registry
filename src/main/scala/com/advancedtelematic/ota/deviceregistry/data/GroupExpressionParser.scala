package com.advancedtelematic.ota.deviceregistry.data

import atto._
import Atto._
import cats.data.NonEmptyList
import cats.implicits._
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.DeviceTable
import slick.lifted.Rep
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupExpression
import com.advancedtelematic.ota.deviceregistry.data.GroupExpressionAST.{
  And,
  DeviceContains,
  Expression,
  Or
}
import cats.syntax.either._
import com.advancedtelematic.ota.deviceregistry.common.Errors

object GroupExpressionAST {
  sealed trait Expression
  case class DeviceContains(word: String) extends Expression
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

  lazy val expression: Parser[Expression] = or | and | leftExpression

  lazy val leftExpression: Parser[Expression] = deviceIdContains | brackets

  lazy val brackets: Parser[Expression] = parens(expression)

  lazy val parser: Parser[Expression] = expression <~ endOfInput

  lazy val deviceIdContains: Parser[Expression] = for {
    _ <- string("deviceid")
    _ <- skipWhitespace
    _ <- string("contains")
    _ <- skipWhitespace
    b <- takeWhile1(_.isLetterOrDigit)
  } yield DeviceContains(b)

  lazy val and: Parser[Expression] = for {
    a <- leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("and")) ~> token(leftExpression))
  } yield And(a :: b)

  lazy val or: Parser[Expression] = for {
    a <- and | leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("or")) ~> token(and | leftExpression))
  } yield Or(a :: b)
}
