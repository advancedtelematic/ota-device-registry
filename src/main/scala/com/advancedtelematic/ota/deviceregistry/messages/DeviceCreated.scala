/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.messages

import java.time.Instant

import cats.syntax.show._
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.ota.deviceregistry.data.DeviceType.DeviceType
import com.advancedtelematic.ota.deviceregistry.data.{DeviceName, DeviceOemId}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

final case class DeviceCreated(namespace: Namespace,
                               uuid: DeviceId,
                               deviceName: DeviceName,
                               deviceId: DeviceOemId,
                               deviceType: DeviceType,
                               timestamp: Instant = Instant.now())

object DeviceCreated {
  implicit val deviceCreatedEncoder: Encoder[DeviceCreated] = deriveEncoder[DeviceCreated]
  implicit val deviceCreatedDecoder: Decoder[DeviceCreated] = deriveDecoder[DeviceCreated]
  implicit val deviceCreatedMessageLike: MessageLike[DeviceCreated] = MessageLike[DeviceCreated](_.uuid.show)
}
