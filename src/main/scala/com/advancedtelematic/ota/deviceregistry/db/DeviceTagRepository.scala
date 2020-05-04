package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.slick.db.SlickAnyVal._
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceTag
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object DeviceTagRepository {

  class DeviceTagTable(tag: Tag) extends Table[DeviceTag](tag, "DeviceTag") {
    def namespace = column[Namespace]("namespace")
    def tagId = column[Int]("tag_id")
    def tagName = column[String]("tag_name")

    def * = (namespace, tagId, tagName).shaped <> ((DeviceTag.apply _).tupled, DeviceTag.unapply)
  }

  val deviceTags = TableQuery[DeviceTagTable]

  def fetchAll(namespace: Namespace): DBIO[Seq[DeviceTag]] =
    deviceTags
      .filter(_.namespace === namespace)
      .result

  def create(namespace: Namespace, tagName: String)
            (implicit ec: ExecutionContext): DBIO[Int] =
    for {
      lastTagId <- deviceTags.filter(_.namespace === namespace).map(_.tagId).max.result
      nextTagId = lastTagId.fold(0)(_ + 1)
      _ <- deviceTags += DeviceTag(namespace, nextTagId, tagName)
    } yield nextTagId

  def rename(namespace: Namespace, tagId: Int, tagName: String): DBIO[Int] =
    deviceTags
    .filter(_.namespace === namespace)
    .filter(_.tagId === tagId)
    .map(_.tagName)
    .update(tagName)
}
