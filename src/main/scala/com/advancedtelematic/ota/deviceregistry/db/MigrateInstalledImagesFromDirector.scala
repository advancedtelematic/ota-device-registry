package com.advancedtelematic.ota.deviceregistry.db

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.SlickUUIDKey._
import com.advancedtelematic.ota.deviceregistry.client.DirectorClient
import com.advancedtelematic.ota.deviceregistry.data.DataType.SoftwareImage
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.devices
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings.namespaceColumnType
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class MigrateInstalledImagesFromDirector(director: DirectorClient)(implicit db: Database,
                                                           ec: ExecutionContext,
                                                           mat: Materializer) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  def saveImage(did: DeviceId, ecuId: EcuIdentifier, image: SoftwareImage): Future[(EcuIdentifier, SoftwareImage)] = {
    _log.info(s"Saving software image from director => ecuId: $ecuId image: $image.")
    db.run(CurrentImageRepository.saveSoftwareImage(did, ecuId, image).map(_ => ecuId -> image))
  }

  def run: Future[Done] = {
    val source = db.stream(devices.map(d => (d.namespace, d.uuid)).result)
    Source
      .fromPublisher(source)
      .mapAsyncUnordered(1) { case (ns, did) => director.getImages(ns, did).map(_.map(did -> _)) }
      .mapConcat(_.toList)
      .mapAsyncUnordered(4) { case (did, (ecuId, image)) =>
        saveImage(did, ecuId, image)
      }
      .runWith {
        Sink.foreach { case (ecuId, image) =>
          _log.info(s"Migrated software image from director => ecuId: $ecuId image: $image.")
        }
      }
  }
}
