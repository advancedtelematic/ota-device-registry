/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import java.time.Instant

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.slick.db.SlickExtensions._
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.data.{CredentialsType, DeviceT, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings._
import com.advancedtelematic.ota.deviceregistry.messages.{DeviceCreated, DevicePublicCredentialsSet}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

object PublicCredentialsRepository {
  case class DevicePublicCredentials(device: Uuid, typeCredentials: CredentialsType, credentials: Array[Byte])

  class PublicCredentialsTable(tag: Tag) extends Table[DevicePublicCredentials](tag, "DevicePublicCredentials") {
    def device            = column[Uuid]("device_uuid")
    def typeCredentials   = column[CredentialsType]("type_credentials")
    def publicCredentials = column[Array[Byte]]("public_credentials")

    def * =
      (device, typeCredentials, publicCredentials).shaped <>
      ((DevicePublicCredentials.apply _).tupled, DevicePublicCredentials.unapply)

    def pk = primaryKey("device_uuid", device)
  }

  val allPublicCredentials = TableQuery[PublicCredentialsTable]

  def findByUuid(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[DevicePublicCredentials] =
    allPublicCredentials
      .filter(_.device === uuid)
      .result
      .failIfNotSingle(Errors.MissingDevicePublicCredentials)

  def update(uuid: Uuid, cType: CredentialsType, creds: Array[Byte])(
      implicit ec: ExecutionContext
  ): DBIO[Unit] =
    allPublicCredentials
      .insertOrUpdate(DevicePublicCredentials(uuid, cType, creds))
      .map(_ => ())

  def delete(uuid: Uuid)(implicit ec: ExecutionContext): DBIO[Int] =
    allPublicCredentials.filter(_.device === uuid).delete

  def createDeviceWithPublicCredentials(ns: Namespace, devT: DeviceT, messageBus: MessageBusPublisher)(
      implicit db: Database,
      ec: ExecutionContext
  ): Future[Uuid] =
    (devT.deviceId, devT.credentials) match {
      case (Some(devId), Some(credentials)) => {
        val cType = devT.credentialsType.getOrElse(CredentialsType.PEM)
        val dbact = for {
          (created, uuid) <- DeviceRepository.findUuidFromUniqueDeviceIdOrCreateAction(ns, devId, devT)
          _               <- PublicCredentialsRepository.update(uuid, cType, credentials.getBytes)
        } yield (created, uuid)

        for {
          (created, uuid) <- db.run(dbact.transactionally)
          _ <- if (created) {
            messageBus.publish(
              DeviceCreated(ns, uuid, devT.deviceName, devT.deviceId, devT.deviceType, Instant.now())
            )
          } else { Future.successful(()) }
          _ <- messageBus.publish(
            DevicePublicCredentialsSet(ns, uuid, cType, credentials, Instant.now())
          )
        } yield uuid
      }
      case (None, _) => FastFuture.failed(Errors.RequestNeedsDeviceId)
      case (_, None) => FastFuture.failed(Errors.RequestNeedsCredentials)
    }

}
