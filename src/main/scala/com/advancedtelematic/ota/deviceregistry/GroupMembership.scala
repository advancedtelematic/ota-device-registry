package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupMemberRepository, GroupRepository}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

protected trait GroupMembershipOperations {
  def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]]
  def addGroupMember(groupId: GroupId, deviceId: Uuid): Future[Unit]
  def countDevices(group: Group): Future[Long]
  def removeGroupMember(group: Group, deviceId: Uuid): Future[Unit]
}

protected class DynamicMembership(implicit db: Database, ec: ExecutionContext) extends GroupMembershipOperations {

  override def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    DeviceRepository.searchByDeviceIdContains(group.namespace, group.expression, offset, limit)

  override def addGroupMember(groupId: GroupId, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotAddDeviceToDynamicGroup)

  override def countDevices(group: Group): Future[Long] =
    db.run(DeviceRepository.countByDeviceIdContains(group.namespace, group.expression))

  override def removeGroupMember(group: Group, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotRemoveDeviceFromDynamicGroup)

}

protected class StaticMembership(implicit db: Database, ec: ExecutionContext) extends GroupMembershipOperations {

  override def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    GroupMemberRepository.listDevicesInGroup(group.id, offset, limit)

  override def addGroupMember(groupId: GroupId, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.addGroupMember(groupId, deviceId)).map(_ => ())

  override def countDevices(group: Group): Future[Long] = db.run(GroupMemberRepository.countDevicesInGroup(group.id))

  override def removeGroupMember(group: Group, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.removeGroupMember(group.id, deviceId))

}

class GroupMembership(implicit val db: Database, ec: ExecutionContext) {

  private def run[T](groupId: GroupId)(fn: (Group, GroupMembershipOperations) => Future[T]): Future[T] =
    GroupRepository.findById(groupId).flatMap {
      case g if g.`type` == GroupType.static => fn(g, new StaticMembership())
      case g                                 => fn(g, new DynamicMembership())
    }

  def listDevices(groupId: GroupId, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    run(groupId) { (g, m) =>
      m.listDevices(g, offset, limit)
    }

  def addGroupMember(groupId: GroupId, deviceId: Uuid)(implicit ec: ExecutionContext): Future[Unit] =
    run(groupId) { (g, m) =>
      m.addGroupMember(g.id, deviceId)
    }

  def countDevices(groupId: GroupId): Future[Long] = run(groupId) { (g, m) =>
    m.countDevices(g)
  }

  def removeGroupMember(groupId: GroupId, deviceId: Uuid) = run(groupId) { (g, m) =>
    m.removeGroupMember(g, deviceId)
  }
}
