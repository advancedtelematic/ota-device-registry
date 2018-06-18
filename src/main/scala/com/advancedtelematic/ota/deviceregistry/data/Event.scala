/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import java.time.Instant

import io.circe.{Decoder, Encoder, Json}

final case class EventType(id: String, version: Int)

object EventType {
  implicit val DecoderInstance: Decoder[EventType] = io.circe.generic.semiauto.deriveDecoder
  implicit val EncoderInstance: Encoder[EventType] = io.circe.generic.semiauto.deriveEncoder

}

final case class Event(deviceUuid: Uuid,
                       eventId: String,
                       eventType: EventType,
                       deviceTime: Instant,
                       receivedAt: Instant,
                       payload: Json)

object Event {

  import io.circe.java8.time.{decodeInstant, encodeInstant}
  implicit val EncoderInstance: Encoder[Event] = io.circe.generic.semiauto.deriveEncoder[Event]
  implicit val DecoderInstance: Decoder[Event] = io.circe.generic.semiauto.deriveDecoder[Event]
}
