/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.data
import com.advancedtelematic.ota.deviceregistry.common.{Errors, SlickJsonHelper}
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import SlickMappings._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._

import scala.concurrent.{ExecutionContext, Future}

object GroupInfoRepository extends SlickJsonHelper with ColumnTypes {

  private[this] val defaultLimit = 50L

  // scalastyle:off
  class GroupInfoTable(tag: Tag) extends Table[Group](tag, "DeviceGroup") {
    def id         = column[GroupId]("id", O.PrimaryKey)
    def groupName  = column[Name]("group_name")
    def namespace  = column[Namespace]("namespace")
    def `type`     = column[GroupType]("type")
    def expression = column[Option[GroupExpression]]("expression")

    def * = (id, groupName, namespace, `type`, expression) <> ((Group.apply _).tupled, Group.unapply)
  }
  // scalastyle:on

  val groupInfos = TableQuery[GroupInfoTable]

  def list(namespace: Namespace, offset: Option[Long], limit: Option[Long])(
      implicit ec: ExecutionContext
  ): DBIO[PaginationResult[Group]] =
    groupInfos
      .filter(g => g.namespace === namespace)
      .paginateResult(offset.getOrElse(0L), limit.getOrElse(defaultLimit))

  protected def findByName(groupName: Name, namespace: Namespace) =
    groupInfos.filter(r => r.groupName === groupName && r.namespace === namespace)

  def findById(id: GroupId)(implicit db: Database, ec: ExecutionContext): Future[Group] =
    db.run(findByIdAction(id))

  def findByIdAction(id: GroupId)(implicit ec: ExecutionContext): DBIO[Group] =
    groupInfos
      .filter(r => r.id === id)
      .result
      .failIfNotSingle(Errors.MissingGroup)

  def getIdFromName(groupName: Name, namespace: Namespace)(implicit ec: ExecutionContext): DBIO[GroupId] =
    findByName(groupName, namespace)
      .map(_.id)
      .result
      .failIfNotSingle(Errors.MissingGroup)

  def create(id: GroupId,
             groupName: Name,
             namespace: Namespace,
             groupType: GroupType,
             expression: Option[GroupExpression])(
      implicit ec: ExecutionContext
  ): DBIO[GroupId] =
    (groupInfos += data.Group(id, groupName, namespace, groupType, expression))
      .handleIntegrityErrors(Errors.ConflictingGroup)
      .map(_ => id)

  def renameGroup(id: GroupId, newGroupName: Name)(implicit ec: ExecutionContext): DBIO[Unit] =
    groupInfos
      .filter(r => r.id === id)
      .map(_.groupName)
      .update(newGroupName)
      .handleIntegrityErrors(Errors.ConflictingDevice)
      .handleSingleUpdateError(Errors.MissingGroup)

  def groupInfoNamespace(groupId: GroupId)(implicit ec: ExecutionContext): DBIO[Namespace] =
    groupInfos
      .filter(_.id === groupId)
      .map(_.namespace)
      .result
      .failIfNotSingle(Errors.MissingGroup)

  def exists(id: GroupId, namespace: Namespace)(implicit ec: ExecutionContext): DBIO[Group] =
    groupInfos
      .filter(d => d.namespace === namespace && d.id === id)
      .result
      .headOption
      .failIfNone(Errors.MissingGroup)

}
