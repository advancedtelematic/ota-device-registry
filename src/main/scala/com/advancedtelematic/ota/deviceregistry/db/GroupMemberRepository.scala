/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Uuid
import slick.jdbc.MySQLProfile.api._
import slick.lifted.Tag

import scala.concurrent.{ExecutionContext, Future}

object GroupMemberRepository {

  private[this] val defaultLimit = 50L

  final case class GroupMember(groupId: Uuid, deviceUuid: Uuid)

  // scalastyle:off
  class GroupMembersTable(tag: Tag) extends Table[GroupMember](tag, "GroupMembers") {
    def groupId    = column[Uuid]("group_id")
    def deviceUuid = column[Uuid]("device_uuid")
    def deviceFk   = foreignKey("fk_group_members_uuid", deviceUuid, DeviceRepository.devices)(_.uuid)
    def groupFk =
      foreignKey("fk_group_members_group_id", groupId, GroupInfoRepository.groupInfos)(_.id)

    def pk = primaryKey("pk_group_members", (groupId, deviceUuid))

    def * =
      (groupId, deviceUuid).shaped <>
      ((GroupMember.apply _).tupled, GroupMember.unapply)
  }
  // scalastyle:on

  val groupMembers = TableQuery[GroupMembersTable]

  //this method assumes that groupId and deviceId belong to the same namespace
  def addGroupMember(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    (groupMembers += GroupMember(groupId, deviceId))
      .handleIntegrityErrors(Errors.MemberAlreadyExists)

  def addOrUpdateGroupMember(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    groupMembers.insertOrUpdate(GroupMember(groupId, deviceId))

  def removeGroupMember(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): DBIO[Unit] =
    groupMembers
      .filter(r => r.groupId === groupId && r.deviceUuid === deviceId)
      .delete
      .handleSingleUpdateError(Errors.MissingGroup)

  def removeGroupMemberAll(deviceId: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    groupMembers
      .filter(r => r.deviceUuid === deviceId)
      .delete

  def listDevicesInGroup(groupId: Uuid, offset: Option[Long], limit: Option[Long])(
      implicit db: Database,
      ec: ExecutionContext
  ): Future[PaginationResult[Uuid]] =
    db.run(listDevicesInGroupAction(groupId, offset, limit))

  def listDevicesInGroupAction(groupId: Uuid, offset: Option[Long], limit: Option[Long])(
      implicit ec: ExecutionContext
  ): DBIO[PaginationResult[Uuid]] =
    groupMembers
      .filter(_.groupId === groupId)
      .map(_.deviceUuid)
      .paginateResult(offset.getOrElse(0L), limit.getOrElse(defaultLimit))

  def countDevicesInGroup(groupId: Uuid)(implicit ec: ExecutionContext): DBIO[Long] =
    listDevicesInGroupAction(groupId, None, None).map(_.total)

  def listGroupsForDevice(device: Uuid, offset: Option[Long], limit: Option[Long])(
      implicit ec: ExecutionContext
  ): DBIO[PaginationResult[Uuid]] =
    DeviceRepository.findByUuid(device).flatMap { _ =>
      groupMembers
        .filter(_.deviceUuid === device)
        .map(_.groupId)
        .paginateResult(offset.getOrElse(0L), limit.getOrElse(50L))
    }

  def removeDeviceFromAllGroups(deviceUuid: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    groupMembers
      .filter(_.deviceUuid === deviceUuid)
      .delete
}
