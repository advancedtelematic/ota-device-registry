package com.advancedtelematic.ota.deviceregistry.util

import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.client.{SotaCoreBlacklistedPackage, SotaCoreClient}
import com.advancedtelematic.ota.deviceregistry.data.DataType.PackageListItem

import scala.concurrent.Future

class FakeSotaCoreClient extends SotaCoreClient {

  private val blacklistedPackages = new ConcurrentHashMap[Namespace, Seq[SotaCoreBlacklistedPackage]]()

  def addBlacklistedPackages(ns: Namespace, newBlacklistedPackages: Seq[SotaCoreBlacklistedPackage]): Unit =
    blacklistedPackages.put(ns, newBlacklistedPackages)

  override def getBlacklistedPackages(ns: Namespace): Future[Seq[PackageListItem]] =
    FastFuture.successful {
      blacklistedPackages
        .getOrDefault(ns, Seq.empty)
        .map(sp => PackageListItem(ns, sp.packageId, sp.comment))
    }
}
