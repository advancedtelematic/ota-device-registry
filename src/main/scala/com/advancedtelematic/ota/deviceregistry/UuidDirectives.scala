/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.server.{
  AuthorizationFailedRejection,
  Directive1,
  Directives,
  PathMatcher1,
  ValidationRejection
}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatcher.{Matched, Unmatched}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import eu.timepit.refined.api.{Refined, Validate}

import scala.concurrent.Future
import scala.reflect.ClassTag

object UuidDirectives {

  final class RefinedMatcher[P] {
    import Directives._
    import eu.timepit.refined.refineV
    def apply[T](pm: PathMatcher1[T])(implicit p: Validate.Plain[T, P], ev: ClassTag[T]): Directive1[T Refined P] =
      extractRequestContext.flatMap[Tuple1[T Refined P]] { ctx =>
        pm(ctx.unmatchedPath) match {
          case Matched(rest, Tuple1(t: T)) =>
            refineV[P](t) match {
              case Left(err)      => reject(ValidationRejection(err))
              case Right(refined) => provide(refined) & mapRequestContext(_ withUnmatchedPath rest)
            }
          case Unmatched => reject
        }
      }
  }
  val extractUuid: Directive1[Uuid] = refined[Uuid.Valid](Slash ~ Segment).map(Uuid(_))
  val extractRefinedUuid            = refined[Uuid.Valid](Slash ~ Segment)

  def refined[P]: RefinedMatcher[P] = new RefinedMatcher[P]
  def allowExtractor[T](namespaceExtractor: Directive1[AuthedNamespaceScope],
                        extractor: Directive1[T],
                        allowFn: (T => Future[Namespace])): Directive1[T] =
    (extractor & namespaceExtractor).tflatMap {
      case (value, ans) =>
        onSuccess(allowFn(value)).flatMap {
          case namespace if namespace == ans.namespace =>
            provide(value)
          case _ =>
            reject(AuthorizationFailedRejection)
        }
    }
}
