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
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import cats.syntax.either._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
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
                    (implicit ec: ExecutionContext, db: Database, materializer: ActorMaterializer) extends Directives {

  private val DEVICE_OEM_ID_MAX_BYTES = 128
  private val FILTER_EXISTING_DEVICES_BATCH_SIZE = 50

  private val GroupIdPath = {
    def groupAllowed(groupId: GroupId): Future[Namespace] = db.run(GroupInfoRepository.groupInfoNamespace(groupId))
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

  val groupMembership = new GroupMembership()

  def getDevicesInGroup(groupId: GroupId): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      complete(groupMembership.listDevices(groupId, offset, limit))
    }

  def listGroups(ns: Namespace, offset: Option[Long], limit: Option[Long], sortBy: SortBy, nameContains: Option[String]): Route =
    complete(db.run(GroupInfoRepository.list(ns, offset, limit, sortBy, nameContains)))

  def getGroup(groupId: GroupId): Route =
    complete(db.run(GroupInfoRepository.findByIdAction(groupId)))

  def createGroup(groupName: GroupName,
                  namespace: Namespace,
                  groupType: GroupType,
                  expression: Option[GroupExpression]): Route =
    complete(StatusCodes.Created -> groupMembership.create(groupName, namespace, groupType, expression))

  def createGroupWithDevices(groupName: GroupName,
                             namespace: Namespace,
                             byteSource: Source[ByteString, Any])
                            (implicit materializer: ActorMaterializer): Route = {
    val deviceUuids = byteSource
      .via(Framing.delimiter(ByteString("\n"), DEVICE_OEM_ID_MAX_BYTES))
      .map(_.utf8String.toString)
      .map(DeviceOemId)
      .batch(FILTER_EXISTING_DEVICES_BATCH_SIZE, Set(_))(_ + _)
      .map(DeviceRepository.filterExisting(namespace, _))
      .runWith(Sink.seq)
      .flatMap(dbActions => db.run(DBIO.sequence(dbActions)))
      .map(_.flatten)

    val createGroupAndAddDevices =
      for {
        uuids <- deviceUuids
        gid <- groupMembership.create(groupName, namespace, GroupType.static, None)
        _ <- Future.traverse(uuids)(uuid => groupMembership.addGroupMember(gid, uuid))
      } yield gid

    complete(StatusCodes.Created -> createGroupAndAddDevices)
  }

  def renameGroup(groupId: GroupId, newGroupName: GroupName): Route =
    complete(db.run(GroupInfoRepository.renameGroup(groupId, newGroupName)))

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
        (scope.get & parameters(('offset.as[Long].?, 'limit.as[Long].?, 'sortBy.as[SortBy].?, 'nameContains.as[String].?))) {
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
  import GroupType._
  import com.advancedtelematic.circe.CirceInstances._
  import io.circe.generic.semiauto._

  implicit val createGroupEncoder: Encoder[CreateGroup] = deriveEncoder
  implicit val createGroupDecoder: Decoder[CreateGroup] = deriveDecoder
}