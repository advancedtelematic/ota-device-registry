/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.common

import com.advancedtelematic.libats.data.ErrorCode
import com.advancedtelematic.libats.http.Errors.{EntityAlreadyExists, MissingEntity, RawError}
import com.advancedtelematic.ota.deviceregistry.data.Group
import com.advancedtelematic.ota.deviceregistry.db.GroupMemberRepository.GroupMember
import com.advancedtelematic.ota.deviceregistry.db.PublicCredentialsRepository.DevicePublicCredentials
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository.SystemInfo

object Errors {
  import akka.http.scaladsl.model.StatusCodes

  object Codes {
    val MissingDevice           = ErrorCode("missing_device")
    val ConflictingDevice       = ErrorCode("conflicting_device")
    val SystemInfoAlreadyExists = ErrorCode("system_info_already_exists")
    val MissingGroupInfo        = ErrorCode("missing_group_info")
    val GroupAlreadyExists      = ErrorCode("group_already_exists")
    val MemberAlreadyExists     = ErrorCode("device_already_a_group_member")
    val RequestNeedsDeviceId    = ErrorCode("reguest_needs_deviceid")
    val RequestNeedsCredentials = ErrorCode("request_needs_credentials")
  }

  val MissingDevice = RawError(Codes.MissingDevice, StatusCodes.NotFound, "device doesn't exist")
  val ConflictingDevice =
    RawError(Codes.ConflictingDevice, StatusCodes.Conflict, "deviceId or deviceName is already in use")
  val MissingSystemInfo     = MissingEntity[SystemInfo]
  val ConflictingSystemInfo = EntityAlreadyExists[SystemInfo]

  val MissingGroup        = MissingEntity[Group]
  val ConflictingGroup    = EntityAlreadyExists[Group]
  val MemberAlreadyExists = EntityAlreadyExists[GroupMember]

  val MissingDevicePublicCredentials = MissingEntity[DevicePublicCredentials]
  val RequestNeedsDeviceId =
    RawError(Codes.RequestNeedsDeviceId, StatusCodes.BadRequest, "request should contain deviceId")
  val RequestNeedsCredentials =
    RawError(Codes.RequestNeedsCredentials, StatusCodes.BadRequest, "request should contain credentials")
}
