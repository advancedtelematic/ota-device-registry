package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.data.Group._
import com.advancedtelematic.ota.deviceregistry.data.SortBy
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.db.GroupInfoRepository.GroupInfoTable
import slick.jdbc.MySQLProfile.api._

object DbOps {
  implicit def sortBySlickOrderedConversion(sortBy: SortBy): GroupInfoTable => slick.lifted.Ordered =
    sortBy match {
      case SortBy.Name      => table => table.groupName.asc
      case SortBy.CreatedAt => table => table.createdAt.desc
    }

  implicit class PaginationResultOps(x: Option[Long]) {
    def orDefaultOffset: Long  = x.getOrElse(0L)
    def orDefaultLimit: Long   = x.getOrElse(50L)
  }
}
