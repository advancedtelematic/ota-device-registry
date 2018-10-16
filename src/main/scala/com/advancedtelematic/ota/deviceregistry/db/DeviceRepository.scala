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
import SlickMappings._
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT

import scala.concurrent.ExecutionContext

object DeviceRepository {
  val defaultLimit = 50
  val maxLimit     = 1000

  import Device._
  import com.advancedtelematic.libats.slick.db.SlickAnyVal._

  private[this] implicit val DeviceStatusColumnType =
    MappedColumnType.base[DeviceStatus.Value, String](_.toString, DeviceStatus.withName)

  // scalastyle:off
  class DeviceTable(tag: Tag) extends Table[Device](tag, "Device") {
    def namespace    = column[Namespace]("namespace")
    def id         = column[DeviceId]("uuid")
    def oemId     = column[DeviceOemId]("device_id")
    def name   = column[DeviceName]("device_name")
    def deviceType   = column[DeviceType]("device_type")
    def lastSeen     = column[Option[Instant]]("last_seen")
    def createdAt    = column[Instant]("created_at")
    def activatedAt  = column[Option[Instant]]("activated_at")
    def deviceStatus = column[DeviceStatus]("device_status")

    def * =
      (namespace, id, oemId, name, deviceType, lastSeen, createdAt, activatedAt, deviceStatus).shaped <> ((Device.apply _).tupled, Device.unapply)

    def pk = primaryKey("uuid", id)
  }

  // scalastyle:on
  val devices = TableQuery[DeviceTable]

  def create(ns: Namespace, device: DeviceT)(implicit ec: ExecutionContext): DBIO[DeviceId] = {
    val uuid = DeviceId.generate
    val dbDevice = Device(ns, uuid, device.oemId, device.name, device.deviceType, createdAt = Instant.now())

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
        case Seq(d) => DBIO.successful((false, d.id))
        case _      => DBIO.failed(Errors.ConflictingDevice)
      }
    } yield (created, uuid)

  def exists(ns: Namespace, id: DeviceId)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(d => d.namespace === ns && d.id === id)
      .result
      .headOption
      .flatMap(_.fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def findByDeviceId(ns: Namespace, deviceId: DeviceOemId)(implicit ec: ExecutionContext): DBIO[Seq[Device]] =
    devices
      .filter(d => d.namespace === ns && d.oemId === deviceId)
      .result

  def searchByDeviceIdContains(ns: Namespace, expression: GroupExpression)(
      implicit db: Database,
      ec: ExecutionContext
  ): DBIO[Seq[DeviceId]] = {
    val filter = GroupExpressionAST.compileToSlick(expression)

    devices
      .filter(_.namespace === ns)
      .filter(filter)
      .map(_.id)
      .result
  }

  def countDevicesForExpression(ns: Namespace, exp: GroupExpression)(implicit db: Database, ec: ExecutionContext): DBIO[Int] =
    devices
      .filter(_.namespace === ns)
      .filter(GroupExpressionAST.compileToSlick(exp))
      .length
      .result

  def search(ns: Namespace,
             regEx: Option[String Refined Regex],
             groupId: Option[GroupId],
             offset: Option[Long],
             limit: Option[Long])(implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] = {
    val byNamespace = devices.filter(d => d.namespace === ns)
    val byOptionalRegex = regEx match {
      case Some(re) => byNamespace.filter(d => regex(d.name, re))
      case None     => byNamespace
    }
    val byOptionalGroupId = groupId match {
      case Some(gid) =>
        for {
          gm <- GroupMemberRepository.groupMembers if gm.groupId === gid
          d  <- byOptionalRegex if gm.deviceUuid === d.id
        } yield d
      case None => byOptionalRegex
    }

    byOptionalGroupId.paginateAndSortResult(_.name,
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
      case Some(re) => byNamespace.filter(d => regex(d.name, re))
      case None     => byNamespace
    }
    val grouped     = GroupMemberRepository.groupMembers.map(_.deviceUuid)
    val byUngrouped = byOptionalRegex.filterNot(_.id in grouped)

    byUngrouped.paginateAndSortResult(_.name,
                                      offset.getOrElse(0L),
                                      limit.getOrElse[Long](defaultLimit).min(maxLimit))
  }

  def updateDeviceName(ns: Namespace, id: DeviceId, deviceName: DeviceName)(
      implicit ec: ExecutionContext
  ): DBIO[Unit] =
    devices
      .filter(_.id === id)
      .map(d => d.name)
      .update(deviceName)
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .handleSingleUpdateError(Errors.MissingDevice)

  def findByUuid(id: DeviceId)(implicit ec: ExecutionContext): DBIO[Device] =
    devices
      .filter(_.id === id)
      .result
      .headOption
      .flatMap(_.fold[DBIO[Device]](DBIO.failed(Errors.MissingDevice))(DBIO.successful))

  def updateLastSeen(id: DeviceId, when: Instant)(implicit ec: ExecutionContext): DBIO[(Boolean, Namespace)] = {
    val sometime = Some(when)

    val dbIO = for {
      (ns, activatedAt) <- devices
        .filter(_.id === id)
        .map(x => (x.namespace, x.activatedAt))
        .result
        .failIfNotSingle(Errors.MissingDevice)
      _ <- devices.filter(_.id === id).map(x => (x.lastSeen, x.activatedAt)).update((sometime, activatedAt.orElse(sometime)))
    } yield (activatedAt.isEmpty, ns)

    dbIO.transactionally
  }

  def delete(ns: Namespace, id: DeviceId)(implicit ec: ExecutionContext): DBIO[Unit] = {
    val dbIO = for {
      _ <- exists(ns, id)
      _ <- EventJournal.deleteEvents(id)
      _ <- GroupMemberRepository.removeGroupMemberAll(id)
      _ <- PublicCredentialsRepository.delete(id)
      _ <- SystemInfoRepository.delete(id)
      _ <- devices.filter(d => d.namespace === ns && d.id === id).delete
    } yield ()

    dbIO.transactionally
  }

  def deviceNamespace(id: DeviceId)(implicit ec: ExecutionContext): DBIO[Namespace] =
    devices
      .filter(_.id === id)
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

  def setDeviceStatus(id: DeviceId, status: DeviceStatus)(implicit ec: ExecutionContext): DBIO[Unit] =
    devices
      .filter(_.id === id)
      .map(_.deviceStatus)
      .update(status)
      .handleSingleUpdateError(Errors.MissingDevice)
}
