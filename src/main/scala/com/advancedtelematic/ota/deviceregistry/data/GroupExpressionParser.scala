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
import com.advancedtelematic.ota.deviceregistry.data.GroupExpAST.{And, DeviceContains, GroupExp, Or}
import cats.syntax.either._
import com.advancedtelematic.ota.deviceregistry.common.Errors

import scala.util.Try

object GroupExpAST {
  sealed trait GroupExp
  case class DeviceContains(word: String) extends GroupExp
  case class Or(cond: NonEmptyList[GroupExp])  extends GroupExp
  case class And(cond: NonEmptyList[GroupExp]) extends GroupExp

  def apply(refinedExpression: GroupExpression): Try[DeviceTable => Rep[Boolean]] = {
    GroupExpressionParser.parser.parse(refinedExpression.value).done.either
      .leftMap(Errors.InvalidGroupExpression)
      .map(eval)
      .toTry
  }

  def compileToScala(refinedExpression: GroupExpression): Try[Device => Boolean] = {
    GroupExpressionParser.parser.parse(refinedExpression.value).done.either
      .leftMap(Errors.InvalidGroupExpression)
      .map(evalToScala)
      .toTry
  }

  def evalToScala(exp: GroupExp): Device => Boolean = exp match {
    case DeviceContains(word) =>  (d: Device) =>
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

  def eval(exp: GroupExp): DeviceTable => Rep[Boolean] = exp match {
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
  lazy val expression: Parser[GroupExp] = or | and | leftExpression

  lazy val leftExpression: Parser[GroupExp] = deviceIdContains | brackets

  lazy val brackets: Parser[GroupExp] = parens(expression)

  lazy val parser: Parser[GroupExp] = expression <~ endOfInput

  lazy val deviceIdContains: Parser[GroupExp] = for {
    _ <- string("deviceid")
    _ <- skipWhitespace
    _ <- string("contains")
    _ <- skipWhitespace
    b <- takeWhile1(_.isLetterOrDigit)
  } yield DeviceContains(b)

  lazy val and: Parser[GroupExp] = for {
    a <- leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("and")) ~> token(leftExpression))
  } yield And(a :: b)

  lazy val or: Parser[GroupExp] = for {
    a <- and | leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("or")) ~> token(leftExpression))
  } yield Or(a :: b)
}
