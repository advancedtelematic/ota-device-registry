/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.messages

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.ota.deviceregistry.data.Event
import io.circe.{Decoder, Encoder}

final case class DeviceEventMessage(namespace: Namespace, event: Event)

object DeviceEventMessage {
  import io.circe.syntax._
  implicit val EncoderInstance: Encoder[DeviceEventMessage] = Encoder { x =>
    Event.EncoderInstance(x.event).mapObject(_.add("namespace", x.namespace.get.asJson))
  }

  implicit val DecoderInstance: Decoder[DeviceEventMessage] = Decoder { c =>
    for {
      event <- c.as[Event]
      ns    <- c.get[String]("namespace").map(Namespace.apply)
    } yield DeviceEventMessage(ns, event)
  }

  implicit val MessageLikeInstance = new MessageLike[DeviceEventMessage] {
    override def id(v: DeviceEventMessage): String = v.namespace.get

    override implicit val encoder: Encoder[DeviceEventMessage] = EncoderInstance
    override implicit val decoder: Decoder[DeviceEventMessage] = DecoderInstance
  }
}
