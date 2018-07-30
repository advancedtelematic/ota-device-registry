package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupInfoRepository, GroupMemberRepository}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

protected trait GroupMembershipOperations {
  def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]]
  def addMember(groupId: GroupId, deviceId: Uuid): Future[Unit]
  def countDevices(group: Group): Future[Long]
  def removeMember(group: Group, deviceId: Uuid): Future[Unit]
}

protected class DynamicMembership(implicit db: Database, ec: ExecutionContext) extends GroupMembershipOperations {

  override def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    db.run(DeviceRepository.searchByDeviceIdContains(group.namespace, group.expression, offset, limit))

  override def addMember(groupId: GroupId, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotAddDeviceToDynamicGroup)

  override def countDevices(group: Group): Future[Long] =
    db.run(DeviceRepository.countByDeviceIdContains(group.namespace, group.expression))

  override def removeMember(group: Group, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotRemoveDeviceFromDynamicGroup)
}

protected class StaticMembership(implicit db: Database, ec: ExecutionContext) extends GroupMembershipOperations {

  override def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    db.run(GroupMemberRepository.listDevicesInGroup(group.id, offset, limit))

  override def addMember(groupId: GroupId, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.addGroupMember(groupId, deviceId)).map(_ => ())

  override def countDevices(group: Group): Future[Long] = db.run(GroupMemberRepository.countDevicesInGroup(group.id))

  override def removeMember(group: Group, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.removeGroupMember(group.id, deviceId))
}

class GroupMembership(implicit val db: Database, ec: ExecutionContext) {

  private def runGroupOperation[T](groupId: GroupId)(fn: (Group, GroupMembershipOperations) => Future[T]): Future[T] =
    GroupInfoRepository.findById(groupId).flatMap {
      case g if g.`type` == GroupType.static => fn(g, new StaticMembership())
      case g                                 => fn(g, new DynamicMembership())
    }

  def create(groupId: GroupId,
             name: Group.Name,
             namespace: Namespace,
             groupType: GroupType,
             expression: Option[GroupExpression]) = db.run {
    GroupInfoRepository.create(groupId, name, namespace, groupType, expression)
  }

  def listDevices(groupId: GroupId, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    runGroupOperation(groupId) { (g, m) =>
      m.listDevices(g, offset, limit)
    }

  def addGroupMember(groupId: GroupId, deviceId: Uuid)(implicit ec: ExecutionContext): Future[Unit] =
    runGroupOperation(groupId) { (g, m) =>
      m.addMember(g.id, deviceId)
    }

  def countDevices(groupId: GroupId): Future[Long] = runGroupOperation(groupId) { (g, m) =>
    m.countDevices(g)
  }

  def removeGroupMember(groupId: GroupId, deviceId: Uuid): Future[Unit] = runGroupOperation(groupId) { (g, m) =>
    m.removeMember(g, deviceId)
  }
}
