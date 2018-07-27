/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyPath._
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{GroupMembership, GroupRepository}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class GroupsResource(namespaceExtractor: Directive1[AuthedNamespaceScope], deviceNamespaceAuthorizer: Directive1[Uuid])(
    implicit db: Database,
    ec: ExecutionContext
) extends Directives {

  import UuidDirectives._
  import com.advancedtelematic.libats.http.RefinedMarshallingSupport._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val allowGroupPath =
    pathPrefix(GroupId.Path).flatMap { groupId =>
      allowExtractor(namespaceExtractor, provide(groupId), groupAllowed)
    }

  private def groupAllowed(groupId: GroupId): Future[Namespace] =
    GroupRepository.getNamespace(groupId)

  val groupMembership = new GroupMembership()

  def getDevicesInGroup(id: GroupId): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      complete(groupMembership.listDevices(id, offset, limit))
    }

  def listGroups(ns: Namespace): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      complete(GroupRepository.list(ns, offset, limit))
    }

  def getGroup(id: GroupId): Route = complete(GroupRepository.findById(id))

  def createGroup(id: GroupId,
                  groupName: Name,
                  ns: Namespace,
                  groupType: GroupType,
                  expression: Option[GroupExpression]): Route =
    complete(StatusCodes.Created -> GroupRepository.create(id, groupName, ns, groupType, expression))

  def renameGroup(id: GroupId, newGroupName: Name): Route =
    complete(GroupRepository.rename(id, newGroupName))

  def countDevices(groupId: GroupId): Route =
    complete(groupMembership.countDevices(groupId))

  def addDeviceToGroup(groupId: GroupId, deviceId: Uuid): Route =
    complete(groupMembership.addGroupMember(groupId, deviceId))

  def removeDeviceFromGroup(groupId: GroupId, deviceId: Uuid): Route =
    complete(groupMembership.removeGroupMember(groupId, deviceId))

  implicit val groupTypeParamUnmarshaller: Unmarshaller[String, GroupType] =
    Unmarshaller.strict[String, GroupType](GroupType.withName)

  val route: Route =
    (pathPrefix("device_groups") & namespaceExtractor) { ns =>
      val scope = Scopes.devices(ns)
      (scope.post & parameter('groupName.as[Name]) & parameter('type.as[GroupType]) & parameter(
        'expression.as[GroupExpression].?
      ) & pathEnd) { (groupName, `type`, expression) =>
        createGroup(GroupId.generate(), groupName, ns.namespace, `type`, expression)
      } ~
      (scope.get & pathEnd) {
        listGroups(ns.namespace)
      } ~
      (scope.get & allowGroupPath & pathEndOrSingleSlash) { groupId =>
        getGroup(groupId)
      } ~
      (scope.get & allowGroupPath & path("devices")) { groupId =>
        getDevicesInGroup(groupId)
      } ~
      allowGroupPath { groupId =>
        (scope.post & pathPrefix("devices") & deviceNamespaceAuthorizer) { deviceId =>
          addDeviceToGroup(groupId, deviceId)
        } ~
        (scope.delete & pathPrefix("devices") & deviceNamespaceAuthorizer) { deviceId =>
          removeDeviceFromGroup(groupId, deviceId)
        } ~
        (scope.put & path("rename") & parameter('groupName.as[Name])) { groupName =>
          renameGroup(groupId, groupName)
        } ~
        (scope.get & path("count") & pathEnd) {
          countDevices(groupId)
        }
      }
    }
}
