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
import com.advancedtelematic.ota.deviceregistry.data.GroupExpAST.{GroupExpression, And, Or, DeviceContains}

object GroupExpAST {
  sealed trait GroupExpression
  case class DeviceContains(word: String) extends GroupExpression
  case class Or(cond: NonEmptyList[GroupExpression])  extends GroupExpression
  case class And(cond: NonEmptyList[GroupExpression]) extends GroupExpression

  def eval(exp: GroupExpression): DeviceTable => Rep[Boolean] = exp match {
    case DeviceContains(word) =>
      (d: DeviceTable) =>
        d.deviceId.mappedTo[String].like("%" + word + "%")

    case Or(conds) =>
      val cc = conds.map(eval)
      cc.reduceLeft { (a, b) =>
        (d: DeviceTable) => a(d) || b(d)
      }
    case And(conds) =>
      val cc = conds.map(eval)

      cc.reduceLeft { (a, b) =>
        (d: DeviceTable) => a(d) && b(d)
      }
  }
}

object GroupExpressionParser {
  lazy val expression: Parser[GroupExpression] = or | and | leftExpression

  lazy val leftExpression: Parser[GroupExpression] = deviceIdContains | brackets

  lazy val brackets: Parser[GroupExpression] = parens(expression)

  lazy val parser: Parser[GroupExpression] = expression <~ endOfInput

  lazy val deviceIdContains: Parser[GroupExpression] = for {
    _ <- string("deviceid")
    _ <- skipWhitespace
    _ <- string("contains")
    _ <- skipWhitespace
    b <- takeWhile1(_.isLetterOrDigit)
  } yield DeviceContains(b)

  lazy val and: Parser[GroupExpression] = for {
    a <- leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("and")) ~> token(leftExpression))
  } yield And(a :: b)

  lazy val or: Parser[GroupExpression] = for {
    a <- and | leftExpression
    _ <- skipWhitespace
    b <- many1(token(string("or")) ~> token(leftExpression))
  } yield Or(a :: b)
}
