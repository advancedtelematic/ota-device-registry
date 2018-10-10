/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.Operators.regex
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.Device._
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data._
import com.advancedtelematic.ota.deviceregistry.db.DbOps.PaginationResultOps
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object DeviceRepository {

  private[this] implicit val DeviceStatusColumnType =
    MappedColumnType.base[DeviceStatus.Value, String](_.toString, DeviceStatus.withName)

  // scalastyle:off
  class DeviceTable(tag: Tag) extends Table[Device](tag, "Device") {
    def namespace    = column[Namespace]("namespace")
    def uuid         = column[DeviceId]("uuid")
    def deviceName   = column[DeviceName]("device_name")
    def deviceId     = column[DeviceOemId]("device_id")
    def deviceType   = column[DeviceType]("device_type")
    def lastSeen     = column[Option[Instant]]("last_seen")
    def createdAt    = column[Instant]("created_at")
    def activatedAt  = column[Option[Instant]]("activated_at")
    def deviceStatus = column[DeviceStatus]("device_status")

    def * =
      (namespace, uuid, deviceName, deviceId, deviceType, lastSeen, createdAt, activatedAt, deviceStatus).shaped <> ((Device.apply _).tupled, Device.unapply)

    def pk = primaryKey("uuid", uuid)
  }

  // scalastyle:on
  val devices = TableQuery[DeviceTable]

  def create(ns: Namespace, device: DeviceT)(implicit ec: ExecutionContext): DBIO[DeviceId] = {
    val uuid = DeviceId.generate

    val dbDevice = Device(ns,
      uuid,
      device.deviceName,
      device.deviceId,
      device.deviceType,
      createdAt = Instant.now())

    val dbIO = devices += dbDevice
    dbIO
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .andThen { GroupMemberRepository.addDeviceToDynamicGroups(ns, dbDevice) }
      .map(_ => uuid)
      .transactionally
  }

  def findUuidFromUniqueDeviceIdOrCreate(ns: Namespace, deviceId: DeviceOemId, devT: DeviceT)(
      implicit ec: ExecutionContext
  ): DBIO[(Boolean, DeviceId)] =
    for {
      devs <- findByDeviceId(ns, deviceId)
      (created, uuid) <- devs match {
        case Seq()  => create(ns, devT).map((true, _))
        case Seq(d) => DBIO.successful((false, d.uuid))
        case _      => DBIO.failed(Errors.ConflictingDevice)
      }
    } yield (created, uuid)

  def exists(ns: Namespace, uuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(d => d.namespace === ns && d.uuid === uuid)
      .result
      .headOption
      .flatMap(_.fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def findByDeviceId(ns: Namespace, deviceId: DeviceOemId)(implicit ec: ExecutionContext): DBIO[Seq[Device]] =
    devices
      .filter(d => d.namespace === ns && d.deviceId === deviceId)
      .result

  def searchByDeviceIdContains(ns: Namespace, expression: GroupExpression)(
      implicit db: Database,
      ec: ExecutionContext
  ): DBIO[Seq[DeviceId]] = {
    val filter = GroupExpressionAST.compileToSlick(expression)

    devices
      .filter(_.namespace === ns)
      .filter(filter)
      .map(_.uuid)
      .result
  }

  def countDevicesForExpression(ns: Namespace, exp: GroupExpression)(implicit db: Database, ec: ExecutionContext): DBIO[Int] =
    devices
      .filter(_.namespace === ns)
      .filter(GroupExpressionAST.compileToSlick(exp))
      .length
      .result

  private def searchQuery(ns: Namespace, rx: Option[String Refined Regex], groupId: Option[GroupId]) = {
    val byNamespace = devices.filter(_.namespace === ns)
    val regexCondition = rx.map(rexp => (d: DeviceTable) => regex(d.deviceName, rexp))
    val byOptionalRegex = byNamespace.filter(regexCondition.getOrElse((_: DeviceTable) => true.bind))
    val groupIdCondition = groupId.map(gid => GroupMemberRepository.groupMembers.filter(_.groupId === gid).map(_.deviceUuid))
    byOptionalRegex.filter(_.uuid.in(groupIdCondition.getOrElse(devices.map(_.uuid))))
  }

  def search(ns: Namespace, rx: Option[String Refined Regex], groupId: Option[GroupId], offset: Option[Long], limit: Option[Long])
            (implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] =
    searchQuery(ns, rx, groupId).paginateAndSortResult(_.deviceName, offset.orDefaultOffset, limit.orDefaultLimit)

  def searchGrouped(ns: Namespace, groupType: Option[GroupType], offset: Option[Long], limit: Option[Long])
                   (implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] =
    GroupInfoRepository.groupInfos
      .maybeFilter(_.groupType === groupType)
      .join(GroupMemberRepository.groupMembers)
      .on(_.id === _.groupId)
      .join(DeviceRepository.devices)
      .on(_._2.deviceUuid === _.uuid)
      .map(_._2)
      .paginateResult(offset.orDefaultOffset, limit.orDefaultLimit)

  def searchUngrouped(ns: Namespace, rx: Option[String Refined Regex], offset: Option[Long], limit: Option[Long])
                     (implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] = {

    val regexQuery = searchQuery(ns, rx, None).map(_.uuid)
    val ungroupedDevicesQuery = GroupMemberRepository.groupMembers
      .joinRight(DeviceRepository.devices)
      .on(_.deviceUuid === _.uuid)
      .filter(_._1.isEmpty)
      .map(_._2)

    ungroupedDevicesQuery
      .filter(_.uuid.in(regexQuery))
      .paginateResult(offset.orDefaultOffset, limit.orDefaultLimit)
  }

  def updateDeviceName(ns: Namespace, uuid: DeviceId, deviceName: DeviceName)(implicit ec: ExecutionContext): DBIO[Unit] =
    devices
      .filter(_.uuid === uuid)
      .map(r => r.deviceName)
      .update(deviceName)
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .handleSingleUpdateError(Errors.MissingDevice)

  def findByUuid(uuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(_.uuid === uuid)
      .result
      .headOption
      .flatMap(_.fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def updateLastSeen(uuid: DeviceId, when: Instant)(implicit ec: ExecutionContext): DBIO[(Boolean, Namespace)] = {
    val sometime = Some(when)

    val dbIO = for {
      (ns, activatedAt) <- devices
        .filter(_.uuid === uuid)
        .map(x => (x.namespace, x.activatedAt))
        .result
        .failIfNotSingle(Errors.MissingDevice)
      _ <- devices.filter(_.uuid === uuid).map(x => (x.lastSeen, x.activatedAt)).update((sometime, activatedAt.orElse(sometime)))
    } yield (activatedAt.isEmpty, ns)

    dbIO.transactionally
  }

  def delete(ns: Namespace, uuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Unit] = {
    val dbIO = for {
      _ <- exists(ns, uuid)
      _ <- EventJournal.deleteEvents(uuid)
      _ <- GroupMemberRepository.removeGroupMemberAll(uuid)
      _ <- PublicCredentialsRepository.delete(uuid)
      _ <- SystemInfoRepository.delete(uuid)
      _ <- devices.filter(d => d.namespace === ns && d.uuid === uuid).delete
    } yield ()

    dbIO.transactionally
  }

  def deviceNamespace(uuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Namespace] =
    devices
      .filter(_.uuid === uuid)
      .map(_.namespace)
      .result
      .failIfNotSingle(Errors.MissingDevice)

  def countActivatedDevices(ns: Namespace, start: Instant, end: Instant): DBIO[Int] =
    devices
      .filter(_.namespace === ns)
      .map(_.activatedAt.getOrElse(start.minusSeconds(36000)))
      .filter(activatedAt => activatedAt >= start && activatedAt < end)
      .distinct
      .length
      .result

  def setDeviceStatus(uuid: DeviceId, status: DeviceStatus)(implicit ec: ExecutionContext): DBIO[Unit] =
    devices
      .filter(_.uuid === uuid)
      .map(_.deviceStatus)
      .update(status)
      .handleSingleUpdateError(Errors.MissingDevice)
}
