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

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.ota.deviceregistry.data.Device.{DeviceOemId, DeviceType}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.DeviceName

final case class DeviceCreated(namespace: Namespace,
                               uuid: DeviceId,
                               deviceName: DeviceName,
                               deviceId: DeviceOemId,
                               deviceType: DeviceType,
                               timestamp: Instant = Instant.now())

object DeviceCreated {
  import cats.syntax.show._
  import com.advancedtelematic.libats.codecs.CirceCodecs._
  implicit val EncoderInstance     = io.circe.generic.semiauto.deriveEncoder[DeviceCreated]
  implicit val DecoderInstance     = io.circe.generic.semiauto.deriveDecoder[DeviceCreated]
  implicit val MessageLikeInstance = MessageLike[DeviceCreated](_.uuid.show)
}
