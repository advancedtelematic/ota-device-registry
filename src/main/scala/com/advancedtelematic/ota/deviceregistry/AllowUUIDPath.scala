/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive1, Directives}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.UUIDKey.{UUIDKey, UUIDKeyObj}
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId

import scala.concurrent.Future

object AllowUUIDPath {
  def deviceUUID(namespaceExtractor: Directive1[AuthedNamespaceScope], allowFn: DeviceId => Future[Namespace]): Directive1[DeviceId] =
    apply(DeviceId)(namespaceExtractor, allowFn)

  def apply[T <: UUIDKey](idValue: UUIDKeyObj[T])
                         (namespaceExtractor: Directive1[AuthedNamespaceScope], allowFn: T => Future[Namespace])
                         (implicit gen : idValue.SelfGen): Directive1[T] =
    (Directives.pathPrefix(idValue.Path(gen)) & namespaceExtractor).tflatMap {
      case (value, ans) =>
        onSuccess(allowFn(value)).flatMap {
          case namespace if namespace == ans.namespace =>
            provide(value)
          case _ =>
            reject(AuthorizationFailedRejection)
        }
    }
}
