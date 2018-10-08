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
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data
import com.advancedtelematic.ota.deviceregistry.data.Group
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.db.DbOps.sortBySlickOrderedConversion
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import slick.jdbc.MySQLProfile.api._


import scala.concurrent.{ExecutionContext, Future}

object GroupInfoRepository {
  // scalastyle:off
  class GroupInfoTable(tag: Tag) extends Table[Group](tag, "DeviceGroup") {
    def id         = column[GroupId]("id", O.PrimaryKey)
    def groupName  = column[Name]("group_name")
    def namespace  = column[Namespace]("namespace")
    def groupType     = column[GroupType]("type")
    def expression = column[Option[GroupExpression]]("expression")
    def createdAt = column[Instant]("created_at")
    def updatedAt = column[Instant]("updated_at")

    def * = (id, groupName, namespace, groupType, expression) <> ((Group.apply _).tupled, Group.unapply)
  }
  // scalastyle:on

  val groupInfos = TableQuery[GroupInfoTable]

  def list(namespace: Namespace, offset: Long, limit: Long, sortBy: SortBy, nameContains: Option[String])(implicit ec: ExecutionContext): DBIO[PaginationResult[Group]] =
    groupInfos
      .filter(_.namespace === namespace)
      .maybeContains(_.groupName, nameContains)
      .paginateAndSortResult(sortBy, offset, limit)

  def findById(id: GroupId)(implicit db: Database, ec: ExecutionContext): Future[Group] =
    db.run(findByIdAction(id))

  def findByIdAction(id: GroupId)(implicit ec: ExecutionContext): DBIO[Group] =
    groupInfos
      .filter(r => r.id === id)
      .result
      .failIfNotSingle(Errors.MissingGroup)

  def create(id: GroupId, groupName: Name, namespace: Namespace, groupType: GroupType, expression: Option[GroupExpression])
            (implicit ec: ExecutionContext): DBIO[GroupId] =
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

}
