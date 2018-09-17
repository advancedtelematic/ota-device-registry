package com.advancedtelematic.ota.deviceregistry.db
import com.advancedtelematic.libats.slick.codecs.SlickRefined._
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.data.Group._
import com.advancedtelematic.ota.deviceregistry.data.{SortBy, SortByCreatedAt, SortByName}
import com.advancedtelematic.ota.deviceregistry.db.GroupInfoRepository.GroupInfoTable
import slick.jdbc.MySQLProfile.api._

object DbOps {
  implicit def sortBySlickOrderedConversion(sortBy: SortBy): GroupInfoTable => slick.lifted.Ordered =
    sortBy match {
      case SortByName      => table => table.groupName.asc
      case SortByCreatedAt => table => table.createdAt.desc
    }
}
