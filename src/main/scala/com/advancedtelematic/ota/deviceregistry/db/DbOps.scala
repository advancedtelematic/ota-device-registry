package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.{Limit, Offset}
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.ota.deviceregistry.data.SortBy
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.DeviceTable
import com.advancedtelematic.ota.deviceregistry.db.GroupInfoRepository.GroupInfoTable
import slick.jdbc.MySQLProfile.api._

object DbOps {
  implicit def sortBySlickOrderedConversion(sortBy: SortBy): GroupInfoTable => slick.lifted.Ordered =
    sortBy match {
      case SortBy.Name      => table => table.groupName.asc
      case SortBy.CreatedAt => table => table.createdAt.desc
    }

  implicit def sortBySlickOrderedDeviceConversion(sortBy: SortBy): DeviceTable => slick.lifted.Ordered =
    sortBy match {
      case SortBy.Name      => table => table.deviceName.asc
      case SortBy.CreatedAt => table => table.createdAt.desc
    }

  implicit class LimitOps(x: Option[Limit]) {
    def orDefaultLimit: Limit = x.getOrElse(Limit(50))
  }

  implicit class OffsetOps(x: Option[Offset]) {
    def orDefaultOffset: Offset = x.getOrElse(Offset(0))
  }
}
