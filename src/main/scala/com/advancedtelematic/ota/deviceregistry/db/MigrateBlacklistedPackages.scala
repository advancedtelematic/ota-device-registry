package com.advancedtelematic.ota.deviceregistry.db

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.advancedtelematic.ota.deviceregistry.client.SotaCoreClient
import com.advancedtelematic.ota.deviceregistry.data.DataType.PackageListItem
import com.advancedtelematic.ota.deviceregistry.db.PackageListItemRepository.packageListItems
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository.devices
import com.advancedtelematic.ota.deviceregistry.db.SlickMappings.namespaceColumnType
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class MigrateBlacklistedPackages(sotaCoreClient: SotaCoreClient)
                                (implicit db: Database, ec: ExecutionContext, mat: Materializer) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  private def createOrUpdateBlacklistedPackage(packageListItem: PackageListItem) = {
    _log.info(s"Inserting or updating blacklisted package: $packageListItem.")
    packageListItems.insertOrUpdate(packageListItem)
  }


  def run: Future[Done] = {
    val source = db.stream(devices.map(_.namespace).distinct.result)
    Source
      .fromPublisher(source)
      .mapAsyncUnordered(1)(sotaCoreClient.getBlacklistedPackages)
      .fold(Seq.empty[PackageListItem])(_ ++ _)
      .map(_.map(createOrUpdateBlacklistedPackage))
      .runForeach(_.map(db.run))
  }
}
