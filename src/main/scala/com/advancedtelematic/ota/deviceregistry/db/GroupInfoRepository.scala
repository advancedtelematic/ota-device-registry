/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry.db

import akka.actor.Scheduler

import java.time.Instant
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{Limit, Offset, PaginationResult}
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupExpression, GroupName, GroupType, TagId}
import com.advancedtelematic.ota.deviceregistry.db.DbOps.{LimitOps, OffsetOps, sortBySlickOrderedConversion}
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

object GroupInfoRepository {
  // scalastyle:off
  class GroupInfoTable(tag: Tag) extends Table[Group](tag, "DeviceGroup") {
    def id         = column[GroupId]("id", O.PrimaryKey)
    def groupName  = column[GroupName]("group_name")
    def namespace  = column[Namespace]("namespace")
    def groupType     = column[GroupType]("type")
    def expression = column[Option[GroupExpression]]("expression")
    def createdAt = column[Instant]("created_at")
    def updatedAt = column[Instant]("updated_at")

    def * = (id, groupName, namespace, createdAt, groupType, expression) <> ((Group.apply _).tupled, Group.unapply)
  }
  // scalastyle:on

  val groupInfos = TableQuery[GroupInfoTable]

  def list(namespace: Namespace, offset: Option[Offset], limit: Option[Limit], sortBy: SortBy, nameContains: Option[String])(implicit ec: ExecutionContext): DBIO[PaginationResult[Group]] =
    groupInfos
      .filter(_.namespace === namespace)
      .maybeContains(_.groupName, nameContains)
      .paginateAndSortResult(sortBy, offset.orDefaultOffset, limit.orDefaultLimit)

  def findById(id: GroupId)(implicit db: Database, ec: ExecutionContext, scheduler: Scheduler): Future[Group] =
    db.runWithRetry(findByIdAction(id))

  def findByIdAction(id: GroupId)(implicit ec: ExecutionContext): DBIO[Group] =
    groupInfos
      .filter(r => r.id === id)
      .result
      .failIfNotSingle(Errors.MissingGroup)

  def create(id: GroupId, groupName: GroupName, namespace: Namespace, groupType: GroupType, expression: Option[GroupExpression])
            (implicit ec: ExecutionContext): DBIO[GroupId] =
    (groupInfos += data.Group(id, groupName, namespace, Instant.now, groupType, expression))
      .handleIntegrityErrors(Errors.ConflictingGroup)
      .map(_ => id)

  def renameGroup(id: GroupId, newGroupName: GroupName)(implicit ec: ExecutionContext): DBIO[Unit] =
    groupInfos
      .filter(_.id === id)
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

  def renameTagIdInExpression(namespace: Namespace, tagId: TagId, newTagId: TagId): DBIO[Int] =
    sqlu"""
          UPDATE DeviceGroup
          SET expression = REPLACE(expression, 'tag(#${tagId.value})', 'tag(#${newTagId.value})')
          WHERE namespace = ${namespace.get} AND expression LIKE '%tag(#${tagId.value})%';
         """

  private[db] def findSmartGroupsUsingTag(namespace: Namespace, tagId: TagId)(implicit ec: ExecutionContext): DBIO[Seq[(GroupId, GroupExpression)]] =
    groupInfos
      .filter(_.namespace === namespace)
      .filter(_.groupType === GroupType.dynamic)
      .filter(gi => gi.expression.mappedTo[String].like(s"%tag(${tagId.value})%"))
      .map(gi => gi.id -> gi.expression.get)
      .result

  private[db] def updateSmartGroupExpression(groupId: GroupId, expression: GroupExpression)(implicit ec: ExecutionContext): DBIO[Unit] =
    groupInfos
      .filter(_.groupType === GroupType.dynamic)
      .filter(_.id === groupId)
      .map(_.expression)
      .update(Some(expression))
      .map(_ => ())
}
