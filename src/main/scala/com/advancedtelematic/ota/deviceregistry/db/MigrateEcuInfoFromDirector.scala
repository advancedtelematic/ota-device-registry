package com.advancedtelematic.ota.deviceregistry.db

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.client.DirectorClient
import com.advancedtelematic.ota.deviceregistry.data.DataType.Ecu
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.devices
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings.namespaceColumnType
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class MigrateEcuInfoFromDirector(director: DirectorClient)(implicit db: Database,
                                                           ec: ExecutionContext,
                                                           mat: Materializer) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  def saveEcu(ecu: Ecu): Future[EcuIdentifier] = {
    _log.info(s"Migrating ECU from director: $ecu.")
    db.run(EcuRepository.ecus.insertOrUpdate(ecu).map(_ => ecu.ecuId))
  }

  def run: Future[Done] = {
    val source = db.stream(devices.map(d => (d.namespace, d.uuid)).result)
    Source
      .fromPublisher(source)
      .mapAsyncUnordered(1) { case (ns, did) => director.getEcuInfo(ns, did).map(_.map((ns, did, _))) }
      .mapConcat(_.toList)
      .mapAsyncUnordered(4) { case (ns, did, ecuInfo) =>
        for {
          pubKey <- director.getPublicKey(ns, did, ecuInfo.id)
          ecuId <- saveEcu(Ecu(did, ecuInfo.id, ecuInfo.hardwareId, ecuInfo.primary, pubKey))
        } yield ecuId
      }
      .runWith {
        Sink.foreach(ecuId => _log.info(s"Migrated ECU $ecuId"))
      }
  }
}
