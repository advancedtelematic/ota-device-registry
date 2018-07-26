package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupInfoRepository, GroupMemberRepository}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

protected trait GroupMembershipT {
  def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]]

  def addGroupMember(groupId: Uuid, deviceId: Uuid): Future[Unit]

  def countDevices(group: Group): Future[Long]
}

protected class DynamicMembership()(implicit db: Database, ec: ExecutionContext) extends GroupMembershipT {
  override def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    DeviceRepository.searchByDeviceIdContains(group.namespace, group.expression, offset, limit)

  override def addGroupMember(groupId: Uuid, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotAddDeviceToDynamicGroup)

  override def countDevices(group: Group): Future[Long] =
    db.run(DeviceRepository.countByDeviceIdContains(group.namespace, group.expression))
}

protected class StaticMembership()(implicit db: Database, ec: ExecutionContext) extends GroupMembershipT {
  override def listDevices(group: Group, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    GroupMemberRepository.listDevicesInGroup(group.id, offset, limit)

  override def addGroupMember(groupId: Uuid, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.addGroupMember(groupId, deviceId)).map(_ => ())

  override def countDevices(group: Group): Future[Long] = db.run(GroupMemberRepository.countDevicesInGroup(group.id))
}

class GroupMembership()(implicit val db: Database, ec: ExecutionContext) {

  private def run[T](groupId: Uuid)(fn: (Group, GroupMembershipT) => Future[T]): Future[T] =
    GroupInfoRepository.findById(groupId).flatMap {
      case g if g.`type` == GroupType.static => fn(g, new StaticMembership())
      case g                                 => fn(g, new DynamicMembership())
    }

  def listDevices(groupId: Uuid, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    run(groupId) { (g, m) =>
      m.listDevices(g, offset, limit)
    }

  def addGroupMember(groupId: Uuid, deviceId: Uuid)(implicit ec: ExecutionContext): Future[Unit] =
    run(groupId) { (g, m) =>
      m.addGroupMember(g.id, deviceId)
    }

  def countDevices(groupId: Uuid): Future[Long] = run(groupId) { (g, m) =>
    m.countDevices(g)
  }

}
