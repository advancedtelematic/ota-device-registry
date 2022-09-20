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

package com.advancedtelematic.ota.deviceregistry.messages

import java.time.Instant

import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType

final case class DevicePublicCredentialsSet(namespace: Namespace,
                                            uuid: DeviceId,
                                            credentialsType: CredentialsType,
                                            credentials: String,
                                            timestamp: Instant = Instant.now())

object DevicePublicCredentialsSet {
  import cats.syntax.show._
  import com.advancedtelematic.libats.codecs.CirceCodecs._
  implicit val MessageLikeInstance = MessageLike.derive[DevicePublicCredentialsSet](_.uuid.show)
}
