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
import com.advancedtelematic.ota.deviceregistry.data.DataType.{CreateDevice, Ecu, SearchParams}
import com.advancedtelematic.ota.deviceregistry.data.Device._
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data._
import com.advancedtelematic.ota.deviceregistry.db.DbOps.PaginationResultOps
import com.advancedtelematic.ota.deviceregistry.db.GroupInfoRepository.groupInfos
import com.advancedtelematic.ota.deviceregistry.db.GroupMemberRepository.groupMembers
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

  def create(ns: Namespace, payload: CreateDevice)(implicit ec: ExecutionContext): DBIO[DeviceId] = {
    val uuid = payload.uuid.getOrElse(DeviceId.generate)
    val newDevice = Device(ns, uuid, payload.deviceName, payload.deviceId, payload.deviceType, createdAt = Instant.now())
    val newEcus = payload.ecus.map(ecu => Ecu(uuid, ecu.ecuId, ecu.ecuType, ecu.ecuId == payload.primaryEcu, ecu.clientKey))

    (devices += newDevice)
      .andThen(EcuRepository.ecus ++= newEcus.toList)
      .andThen(GroupMemberRepository.addDeviceToDynamicGroups(ns, newDevice))
      .map(_ => uuid)
      .transactionally
      .handleIntegrityErrors(Errors.ConflictingDevice)
  }

  def findUuidFromUniqueDeviceIdOrCreate(ns: Namespace, deviceId: DeviceOemId, payload: CreateDevice)(
      implicit ec: ExecutionContext
  ): DBIO[(Boolean, DeviceId)] =
    for {
      devs <- findByDeviceIdQuery(ns, deviceId).result
      (created, uuid) <- devs match {
        case Seq()  => create(ns, payload).map((true, _))
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

  def findByDeviceIdQuery(ns: Namespace, deviceId: DeviceOemId)(implicit ec: ExecutionContext): Query[DeviceTable, Device, Seq] =
    devices.filter(d => d.namespace === ns && d.deviceId === deviceId)

  def searchByExpression(ns: Namespace, expression: GroupExpression)
                        (implicit db: Database, ec: ExecutionContext): DBIO[Seq[DeviceId]] =
    devices
      .filter(_.namespace === ns)
      .filter(GroupExpressionAST.compileToSlick(expression))
      .map(_.uuid)
      .result

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

  private def runQueryWithRegex(ns: Namespace, query: Query[DeviceTable, Device, Seq], rx: Option[String Refined Regex])
                             (implicit ec: ExecutionContext) = {
    val regexQuery = searchQuery(ns, rx, None).map(_.uuid)
    query.filter(_.uuid.in(regexQuery))
  }

  private val groupedDevicesQuery: Option[GroupType] => Query[DeviceTable, Device, Seq] = groupType =>
    groupInfos
      .maybeFilter(_.groupType === groupType)
      .join(groupMembers)
      .on(_.id === _.groupId)
      .join(devices)
      .on(_._2.deviceUuid === _.uuid)
      .map(_._2)
      .distinct

  def search(ns: Namespace, params: SearchParams)(implicit ec: ExecutionContext): DBIO[PaginationResult[Device]] = {
    val query = params match {

      case SearchParams(Some(oemId), _, _, None, None, _, _) =>
        findByDeviceIdQuery(ns, oemId)

      case SearchParams(None, Some(true), gt, None, rx, _, _) =>
        runQueryWithRegex(ns, groupedDevicesQuery(gt), rx)

      case SearchParams(None, Some(false), gt, None, rx, _, _) =>
        val ungroupedDevicesQuery = devices.filterNot(_.uuid.in(groupedDevicesQuery(gt).map(_.uuid)))
        runQueryWithRegex(ns, ungroupedDevicesQuery, rx)

      case SearchParams(None, _, _, gid, rx, _, _) =>
        searchQuery(ns, rx, gid)

      case _ => throw new IllegalArgumentException("Invalid parameter combination.")
    }
    query.paginateAndSortResult(_.deviceName, params.offset.orDefaultOffset, params.limit.orDefaultLimit)
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
      _ <- EventJournal.archiveIndexedEvents(uuid)
      _ <- EventJournal.deleteEvents(uuid)
      _ <- GroupMemberRepository.removeGroupMemberAll(uuid)
      _ <- PublicCredentialsRepository.delete(uuid)
      _ <- SystemInfoRepository.delete(uuid)
      _ <- EcuRepository.delete(uuid)
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
