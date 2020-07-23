package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.SlickAnyVal.stringAnyValSerializer
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.libats.slick.db.SlickValidatedGeneric.validatedStringMapper
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.DataType.TaggedDevice
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.ota.deviceregistry.data.{Device, TagId}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.findByDeviceIdQuery
import com.advancedtelematic.ota.deviceregistry.db.GroupMemberRepository.{addDeviceToDynamicGroups, deleteDynamicGroupsForDevice}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

object TaggedDeviceRepository {

  class TaggedDeviceTable(tag: Tag) extends Table[TaggedDevice](tag, "TaggedDevice") {
    def namespace  = column[Namespace]("namespace")
    def deviceUuid = column[DeviceId]("device_uuid")
    def tagId = column[TagId]("tag_id")
    def tagValue = column[String]("tag_value")

    def * = (namespace, deviceUuid, tagId, tagValue).shaped <> ((TaggedDevice.apply _).tupled, TaggedDevice.unapply)
  }

  val taggedDevices = TableQuery[TaggedDeviceTable]

  def fetchAll(namespace: Namespace): DBIO[Seq[TagId]] =
    taggedDevices
      .filter(_.namespace === namespace)
      .map(_.tagId)
      .distinct
      .result

  def fetchForDevice(deviceUuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Seq[(TagId, String)]] =
    taggedDevices
      .filter(_.deviceUuid === deviceUuid)
      .map(td => td.tagId -> td.tagValue)
      .result

  def updateTagId(namespace: Namespace, tagId: TagId, newTagId: TagId): DBIO[Int] =
    taggedDevices
      .filter(_.namespace === namespace)
      .filter(_.tagId === tagId)
      .map(_.tagId)
      .update(newTagId)

  def delete(deviceUuid: DeviceId)(implicit ec: ExecutionContext): DBIO[Int] =
    taggedDevices
      .filter(_.deviceUuid === deviceUuid)
      .delete

  def tagDeviceByOemId(namespace: Namespace, deviceId: DeviceOemId, tags: Map[TagId, String])
                      (implicit ec: ExecutionContext): DBIO[Unit] = {
    val action = for {
      d <- findByDeviceIdQuery(namespace, deviceId).result.failIfNotSingle(Errors.MissingDevice)
      _ <- refreshDeviceTags(namespace, d, tags)
    } yield ()
    action.transactionally
  }

  def updateDeviceTagValue(namespace: Namespace, deviceId: DeviceId, tagId: TagId, tagValue: String)
                          (implicit ec: ExecutionContext): DBIO[Unit] = {
    val action = for {
      d <- DeviceRepository.exists(namespace, deviceId)
      currentTags <- fetchForDevice(deviceId)
      newTags = currentTags.toMap.updated(tagId, tagValue)
      _ <- refreshDeviceTags(namespace, d, newTags)
    } yield ()
    action.transactionally
  }

  private def refreshDeviceTags(namespace: Namespace, device: Device, tags: Map[TagId, String])
                               (implicit ec: ExecutionContext): DBIO[Unit] = {
    val action = for {
      _ <- setDeviceTags(namespace, device.uuid, tags)
      _ <- deleteDynamicGroupsForDevice(device.uuid)
      _ <- addDeviceToDynamicGroups(namespace, device, tags)
    } yield ()
    action.transactionally
  }

  private def setDeviceTags(ns: Namespace, deviceUuid: DeviceId, tags: Map[TagId, String])
                           (implicit ec: ExecutionContext): DBIO[Unit] = {
    val action = for {
      _ <- taggedDevices.filter(_.deviceUuid === deviceUuid).delete
      _ <- taggedDevices ++= tags.map { case (tid, tv) => TaggedDevice(ns, deviceUuid, tid, tv) }
    } yield ()
    action.transactionally
  }
}
