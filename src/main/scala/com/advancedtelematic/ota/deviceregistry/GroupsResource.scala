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

package com.advancedtelematic.ota.deviceregistry

import akka.actor.Scheduler
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import cats.syntax.either._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{Limit, Offset}
import com.advancedtelematic.libats.http.FromLongUnmarshallers._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.data._
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupInfoRepository}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class GroupsResource(namespaceExtractor: Directive1[AuthedNamespaceScope], deviceNamespaceAuthorizer: Directive1[DeviceId])
                    (implicit ec: ExecutionContext, db: Database, materializer: Materializer, scheduler: Scheduler) extends Directives {

  private val DEVICE_OEM_ID_MAX_BYTES = 128
  private val FILTER_EXISTING_DEVICES_BATCH_SIZE = 50

  private val GroupIdPath = {
    def groupAllowed(groupId: GroupId): Future[Namespace] = db.runWithRetry(GroupInfoRepository.groupInfoNamespace(groupId))
    AllowUUIDPath(GroupId)(namespaceExtractor, groupAllowed)
  }

  implicit val groupTypeUnmarshaller: FromStringUnmarshaller[GroupType] = Unmarshaller.strict(GroupType.withName)
  implicit val groupNameUnmarshaller: FromStringUnmarshaller[GroupName] = Unmarshaller.strict(GroupName.validatedGroupName.from(_).valueOr(throw _))

  implicit val sortByUnmarshaller: FromStringUnmarshaller[SortBy] = Unmarshaller.strict {
    _.toLowerCase match {
      case "name"      => SortBy.Name
      case "createdat" => SortBy.CreatedAt
      case s           => throw new IllegalArgumentException(s"Invalid value for sorting parameter: '$s'.")
    }
  }

  implicit val limitUnmarshaller: Unmarshaller[String, Limit] = getLimitUnmarshaller()

  val groupMembership = new GroupMembership()

  def getDevicesInGroup(groupId: GroupId): Route =
    parameters(('offset.as[Offset].?, 'limit.as[Limit].?)) { (offset, limit) =>
      complete(groupMembership.listDevices(groupId, offset, limit))
    }

  def listGroups(ns: Namespace, offset: Option[Offset], limit: Option[Limit], sortBy: SortBy, nameContains: Option[String]): Route =
    complete(db.runWithRetry(GroupInfoRepository.list(ns, offset, limit, sortBy, nameContains)))

  def getGroup(groupId: GroupId): Route =
    complete(db.runWithRetry(GroupInfoRepository.findByIdAction(groupId)))

  def createGroup(groupName: GroupName,
                  namespace: Namespace,
                  groupType: GroupType,
                  expression: Option[GroupExpression]): Route =
    complete(StatusCodes.Created -> groupMembership.create(groupName, namespace, groupType, expression))

  def createGroupWithDevices(groupName: GroupName,
                             namespace: Namespace,
                             byteSource: Source[ByteString, Any])
                            (implicit materializer: Materializer): Route = {

    val deviceIds = byteSource
      .via(Framing.delimiter(ByteString("\n"), DEVICE_OEM_ID_MAX_BYTES, allowTruncation = true))
      .map(_.utf8String)
      .map(DeviceOemId)
      .runWith(Sink.seq)

    val deviceUuids = deviceIds
      .map(_.grouped(FILTER_EXISTING_DEVICES_BATCH_SIZE).toSeq)
      .map(_.map(_.toSet))
      .map(_.map(DeviceRepository.filterExisting(namespace, _)))
      .flatMap(dbActions => db.runWithRetry(DBIO.sequence(dbActions)))
      .map(_.flatten)
      .recoverWith {
        case _: FramingException =>
          FastFuture.failed(Errors.MalformedInputFile)
      }

    val createGroupAndAddDevices =
      for {
        uuids <- deviceUuids
        gid <- groupMembership.create(groupName, namespace, GroupType.static, None)
        _ <- Future.traverse(uuids)(uuid => groupMembership.addGroupMember(gid, uuid))
      } yield gid

    complete(StatusCodes.Created -> createGroupAndAddDevices)
  }

  def renameGroup(groupId: GroupId, newGroupName: GroupName): Route =
    complete(db.runWithRetry(GroupInfoRepository.renameGroup(groupId, newGroupName)))

  def countDevices(groupId: GroupId): Route =
    complete(groupMembership.countDevices(groupId))

  def addDeviceToGroup(groupId: GroupId, deviceUuid: DeviceId): Route =
    complete(groupMembership.addGroupMember(groupId, deviceUuid))

  def removeDeviceFromGroup(groupId: GroupId, deviceId: DeviceId): Route =
    complete(groupMembership.removeGroupMember(groupId, deviceId))

  val route: Route =
    (pathPrefix("device_groups") & namespaceExtractor) { ns =>
      val scope = Scopes.devices(ns)
      pathEnd {
        (scope.get & parameters(('offset.as[Offset].?, 'limit.as[Limit].?, 'sortBy.as[SortBy].?, 'nameContains.as[String].?))) {
          (offset, limit, sortBy, nameContains) => listGroups(ns.namespace, offset, limit, sortBy.getOrElse(SortBy.Name), nameContains)
        } ~
        scope.post {
          entity(as[CreateGroup]) { req =>
            createGroup(req.name, ns.namespace, req.groupType, req.expression)
          } ~
          (fileUpload("deviceIds") & parameter('groupName.as[GroupName])) {
            case ((_, byteSource), groupName) =>
              createGroupWithDevices(groupName, ns.namespace, byteSource)
          }
        }
      } ~
      GroupIdPath { groupId =>
        (scope.get & pathEndOrSingleSlash) {
          getGroup(groupId)
        } ~
        pathPrefix("devices") {
          scope.get {
            getDevicesInGroup(groupId)
          } ~
          deviceNamespaceAuthorizer { deviceUuid =>
            scope.post {
              addDeviceToGroup(groupId, deviceUuid)
            } ~
            scope.delete {
              removeDeviceFromGroup(groupId, deviceUuid)
            }
          }
        } ~
        (scope.put & path("rename") & parameter('groupName.as[GroupName])) { groupName =>
          renameGroup(groupId, groupName)
        } ~
        (scope.get & path("count") & pathEnd) {
          countDevices(groupId)
        }
      }
    }
}

case class CreateGroup(name: GroupName, groupType: GroupType, expression: Option[GroupExpression])

object CreateGroup {
  import io.circe.generic.semiauto._

  implicit val createGroupEncoder: Encoder[CreateGroup] = deriveEncoder
  implicit val createGroupDecoder: Decoder[CreateGroup] = deriveDecoder
}
