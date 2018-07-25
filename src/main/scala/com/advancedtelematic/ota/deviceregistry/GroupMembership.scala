package com.advancedtelematic.ota.deviceregistry

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupInfoRepository, GroupMemberRepository}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class GroupMembership()(implicit val db: Database, ec: ExecutionContext) {

  def listDevices(groupId: Uuid, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    GroupInfoRepository.findById(groupId).flatMap {
      case Group(_, _, _, GroupType.static, _)     => GroupMemberRepository.listDevicesInGroup(groupId, offset, limit)
      case Group(_, _, ns, GroupType.dynamic, exp) => runDynamicGroup(ns, groupId, exp, offset, limit)
      case g                                       => throw new IllegalArgumentException(s"Unknown group: $g")
    }

  private def runDynamicGroup(ns: Namespace,
                              groupId: Uuid,
                              expression: String,
                              offset: Option[Long],
                              limit: Option[Long]): Future[PaginationResult[Uuid]] =
    DeviceRepository.searchByDeviceIdContains(ns, expression, offset, limit)
}
