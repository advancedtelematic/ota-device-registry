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
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupExpression
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.db.GroupMemberRepository.GroupMember
import com.advancedtelematic.ota.deviceregistry.db.PublicCredentialsRepository.DevicePublicCredentials
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository.SystemInfo

object Errors {
  import akka.http.scaladsl.model.StatusCodes._

  object Codes {
    val MissingDevice                      = ErrorCode("missing_device")
    val ConflictingDevice                  = ErrorCode("conflicting_device")
    val SystemInfoAlreadyExists            = ErrorCode("system_info_already_exists")
    val MissingGroupInfo                   = ErrorCode("missing_group_info")
    val MissingPrimaryEcu                  = ErrorCode("missing_primary_ecu")
    val GroupAlreadyExists                 = ErrorCode("group_already_exists")
    val MemberAlreadyExists                = ErrorCode("device_already_a_group_member")
    val RequestNeedsCredentials            = ErrorCode("request_needs_credentials")
    val CannotAddDeviceToDynamicGroup      = ErrorCode("cannot_add_device_to_dynamic_group")
    val CannotRemoveDeviceFromDynamicGroup = ErrorCode("cannot_remove_device_from_dynamic_group")
    val InvalidGroupExpressionForGroupType = ErrorCode("invalid_group_expression_for_group_type")
    val InvalidGroupExpression             = ErrorCode("invalid_group_expression")
  }

  def InvalidGroupExpression(err: String) = RawError(Codes.InvalidGroupExpression, BadRequest, s"Invalid group expression: '$err'")

  def InvalidGroupExpressionForGroupType(groupType: GroupType, expression: Option[GroupExpression]) =
    RawError(Codes.InvalidGroupExpressionForGroupType,
             BadRequest,
             s"Invalid group expression $expression for group type $groupType")

  val MissingDevice = RawError(Codes.MissingDevice, NotFound, "device doesn't exist")
  val ConflictingDevice =
    RawError(Codes.ConflictingDevice, Conflict, "deviceId or deviceName is already in use")
  val MissingSystemInfo     = MissingEntity[SystemInfo]
  val ConflictingSystemInfo = EntityAlreadyExists[SystemInfo]

  val MissingGroup        = MissingEntity[Group]
  val ConflictingGroup    = EntityAlreadyExists[Group]
  val MemberAlreadyExists = EntityAlreadyExists[GroupMember]

  val MissingDevicePublicCredentials = MissingEntity[DevicePublicCredentials]
  val RequestNeedsCredentials =
    RawError(Codes.RequestNeedsCredentials, BadRequest, "request should contain credentials")

  val CannotAddDeviceToDynamicGroup =
    RawError(Codes.CannotAddDeviceToDynamicGroup, BadRequest, "cannot add device to dynamic group")

  val CannotRemoveDeviceFromDynamicGroup =
    RawError(Codes.CannotRemoveDeviceFromDynamicGroup,
             BadRequest,
             "cannot remove device from dynamic group")

  val MissingPrimaryEcu = RawError(Codes.MissingPrimaryEcu, BadRequest, "Missing primary ecu id in the request.")
}
