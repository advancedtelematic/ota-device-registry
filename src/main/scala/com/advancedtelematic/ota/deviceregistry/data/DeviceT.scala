/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.data

import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId => DeviceUUID}
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceName

/*
 * Device transfer object
 */
final case class DeviceT(
    deviceName: DeviceName,
    deviceUuid: Option[DeviceUUID] = None,
    deviceId: Option[Device.DeviceId] = None,
    deviceType: Device.DeviceType = Device.DeviceType.Other,
    credentials: Option[String] = None,
    credentialsType: Option[CredentialsType] = None
)
