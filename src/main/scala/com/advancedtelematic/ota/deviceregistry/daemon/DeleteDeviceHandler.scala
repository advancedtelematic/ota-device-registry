/*
 * Copyright (c) 2018 HERE Technologies
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.ota.deviceregistry.messages.DeleteDeviceRequest
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeleteDeviceHandler()(implicit val db: Database, ec: ExecutionContext)
    extends (DeleteDeviceRequest => Future[Done]) {
  override def apply(message: DeleteDeviceRequest): Future[Done] =
    db.run(DeviceRepository.delete(message.namespace, message.uuid).map(_ => Done))
}
