package com.advancedtelematic.ota.deviceregistry.db
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.data.Group._
import com.advancedtelematic.ota.deviceregistry.data.SortBy
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.db.GroupInfoRepository.GroupInfoTable
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ColumnOrdered

object DbOps {
  implicit class Sorting(sortBy: SortBy) {
    def groupSorting: GroupInfoTable => ColumnOrdered[_] = sortBy match {
      case SortBy.NAME       => _.groupName.asc
      case SortBy.CREATED_AT => _.createdAt.desc
    }
  }
}
