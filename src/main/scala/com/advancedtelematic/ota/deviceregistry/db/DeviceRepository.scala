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
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.Operators.regex
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId}
import com.advancedtelematic.ota.deviceregistry.data._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Regex
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object DeviceRepository extends ColumnTypes {
  val defaultLimit = 50
  val maxLimit     = 1000

  import Device._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._

  private[this] implicit val DeviceStatusColumnType =
    MappedColumnType.base[DeviceStatus.Value, String](_.toString, DeviceStatus.withName)

  // scalastyle:off
  class DeviceTable(tag: Tag) extends Table[Device](tag, "Device") {
    def namespace    = column[Namespace]("namespace")
    def uuid         = column[Uuid]("uuid")
    def deviceName   = column[DeviceName]("device_name")
    def deviceId     = column[Option[DeviceId]]("device_id")
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

  def create(ns: Namespace, device: DeviceT)(implicit ec: ExecutionContext): DBIO[Uuid] = {
    val uuid: Uuid = device.deviceUuid.getOrElse(Uuid.generate())

    val dbDevice = Device(ns,
      uuid,
      device.deviceName,
      device.deviceId,
      device.deviceType,
      createdAt = Instant.now())

    val dbIO = devices += dbDevice
    dbIO
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .andThen { GroupMemberRepository.addDeviceToDynamicGroups(ns, uuid, dbDevice) }
      .map(_ => uuid)
      .transactionally
  }

  def findUuidFromUniqueDeviceIdOrCreate(ns: Namespace, deviceId: DeviceId, devT: DeviceT)(
      implicit ec: ExecutionContext
  ): DBIO[(Boolean, Uuid)] =
    for {
      devs <- findByDeviceId(ns, deviceId)
      (created, uuid) <- devs match {
        case Seq()  => create(ns, devT).map((true, _))
        case Seq(d) => DBIO.successful((false, d.uuid))
        case _      => DBIO.failed(Errors.ConflictingDevice)
      }
    } yield (created, uuid)

  def exists(ns: Namespace, uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(d => d.namespace === ns && d.uuid === uuid)
      .result
      .headOption
      .flatMap(_.fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def findByDeviceId(ns: Namespace, deviceId: DeviceId)(implicit ec: ExecutionContext): DBIO[Seq[Device]] =
    devices
      .filter(d => d.namespace === ns && d.deviceId === deviceId)
      .result

  def searchByDeviceIdContains(ns: Namespace, expression: GroupExpression)(
      implicit db: Database,
      ec: ExecutionContext
  ): DBIO[Seq[Uuid]] = {
    val filter = GroupExpressionAST.compileToSlick(expression)

    devices
      .filter(_.namespace === ns)
      .filter(filter)
      .map(_.uuid)
      .result
  }

  def search(ns: Namespace,
             regEx: Option[String Refined Regex],
             groupId: Option[GroupId],
             offset: Option[Long],
             limit: Option[Long])(implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] = {
    val byNamespace = devices.filter(d => d.namespace === ns)
    val byOptionalRegex = regEx match {
      case Some(re) => byNamespace.filter(d => regex(d.deviceName, re))
      case None     => byNamespace
    }
    val byOptionalGroupId = groupId match {
      case Some(gid) =>
        for {
          gm <- GroupMemberRepository.groupMembers if gm.groupId === gid
          d  <- byOptionalRegex if gm.deviceUuid === d.uuid
        } yield d
      case None => byOptionalRegex
    }

    byOptionalGroupId.paginateAndSortResult(_.deviceName,
                                            offset.getOrElse(0L),
                                            limit.getOrElse[Long](defaultLimit).min(maxLimit))
  }

  def searchUngrouped(
      ns: Namespace,
      regEx: Option[String Refined Regex],
      offset: Option[Long],
      limit: Option[Long]
  )(implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] = {
    val byNamespace = devices.filter(_.namespace === ns)
    val byOptionalRegex = regEx match {
      case Some(re) => byNamespace.filter(d => regex(d.deviceName, re))
      case None     => byNamespace
    }
    val grouped     = GroupMemberRepository.groupMembers.map(_.deviceUuid)
    val byUngrouped = byOptionalRegex.filterNot(_.uuid in grouped)

    byUngrouped.paginateAndSortResult(_.deviceName,
                                      offset.getOrElse(0L),
                                      limit.getOrElse[Long](defaultLimit).min(maxLimit))
  }

  def update(ns: Namespace, uuid: Uuid, device: DeviceT)(
      implicit ec: ExecutionContext
  ): DBIO[Unit] = {
    val dbIO = devices
      .filter(_.uuid === uuid)
      .map(r => r.deviceName)
      .update(device.deviceName)
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .handleSingleUpdateError(Errors.MissingDevice)

    dbIO
      .andThen {
        GroupMemberRepository.deleteDynamicGroupsForDevice(ns, uuid)
      }
      .andThen {
        DeviceRepository.findByUuid(uuid)
      }
      .flatMap { device =>
        GroupMemberRepository.addDeviceToDynamicGroups(ns, uuid, device)
      }
      .transactionally
  }

  def findByUuid(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(_.uuid === uuid)
      .result
      .headOption
      .flatMap(_.fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def updateLastSeen(uuid: Uuid, when: Instant)(implicit ec: ExecutionContext): DBIO[(Boolean, Namespace)] = {

    val sometime = Some(when)

    val dbIO = for {
      count <- devices
        .filter(_.uuid === uuid)
        .filter(_.activatedAt.isEmpty)
        .map(_.activatedAt)
        .update(sometime)
      _ <- devices.filter(_.uuid === uuid).map(_.lastSeen).update(sometime)
      ns <- devices
        .filter(_.uuid === uuid)
        .map(_.namespace)
        .result
        .failIfNotSingle(Errors.MissingDevice)
    } yield (count > 0, ns)

    dbIO.transactionally
  }

  def delete(ns: Namespace, uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Unit] = {
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

  def deviceNamespace(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Namespace] =
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

  def setDeviceStatus(uuid: Uuid, status: DeviceStatus)(implicit ec: ExecutionContext): DBIO[Unit] =
    devices
      .filter(_.uuid === uuid)
      .map(_.deviceStatus)
      .update(status)
      .handleSingleUpdateError(Errors.MissingDevice)
}
