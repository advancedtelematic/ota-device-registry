package com.advancedtelematic.ota.deviceregistry

import akka.actor.Scheduler
import akka.http.scaladsl.model.StatusCodes.{Created, NoContent}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.{PackageListItem, PackageListItemCount}
import com.advancedtelematic.ota.deviceregistry.data.PackageId
import com.advancedtelematic.ota.deviceregistry.db.PackageListItemRepository
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

/**
  * This "package lists" feature has been migrated from the deprecated
  * ota-core service, where it used to be a "blacklisting" feature. It's
  * been migrated here to terminate ota-core.
  *
  * The feature was not actually blacklisting anything. Instead, it was
  * used to count the number of devices that have a particular package
  * installed. This are the installed packages reported by aktualizr,
  * e.g. 'nano' for a linux distribution, not to be confused with the
  * software images we can install through our system. The count of
  * the packages is displayed in the "Impact" tab of the web app.
  *
  * While moving it here, we've chosen to rename this to "package lists"
  * instead of "blacklisted packages", for lack of a better description
  * of what the feature was being used for.
  */
class PackageListsResource(namespaceExtractor: Directive1[AuthedNamespaceScope],
                           deviceNamespaceAuthorizer: Directive1[DeviceId],
                          )(implicit db: Database, ec: ExecutionContext, scheduler: Scheduler) {

  private val extractPackageId: Directive1[PackageId] =
    pathPrefix(Segment / Segment).as(PackageId.apply)

  private def getPackageListItem(ns: Namespace, packageId: PackageId): Future[PackageListItem] =
    db.runWithRetry(PackageListItemRepository.fetchPackageListItem(ns, packageId))

  private def getPackageListItemCounts(ns: Namespace): Future[Seq[PackageListItemCount]] =
    db.runWithRetry(PackageListItemRepository.fetchPackageListItemCounts(ns))

  private def createPackageListItem(packageListItem: PackageListItem): Future[Unit] =
    db.runWithRetry(PackageListItemRepository.create(packageListItem).map(_ => ()))

  private def updatePackageListItem(patchedPackageListItem: PackageListItem): Future[Unit] =
    db.runWithRetry(PackageListItemRepository.update(patchedPackageListItem).map(_ => ()))

  private def deletePackageListItem(ns: Namespace, packageId: PackageId): Future[Unit] =
    db.runWithRetry(PackageListItemRepository.remove(ns, packageId).map(_ => ()))

  val route: Route = namespaceExtractor { namespace =>
    val scope = Scopes.devices(namespace)
    val ns = namespace.namespace
    pathPrefix("package_lists") {
      pathEnd {
        scope.get {
          complete(getPackageListItemCounts(ns))
        } ~
        (scope.post & entity(as[Namespace => PackageListItem])) { fn =>
          complete(Created -> createPackageListItem(fn(ns)))
        } ~
        // This would better be as a PATCH /package_lists/package-name/package-version, but the UI is already sending this request.
        (scope.put & entity(as[Namespace => PackageListItem])) { fn =>
          complete(NoContent -> updatePackageListItem(fn(ns)))
        }
      } ~
      extractPackageId { packageId =>
        scope.get {
          complete(getPackageListItem(ns, packageId))
        } ~
        scope.delete {
          complete(NoContent -> deletePackageListItem(ns, packageId))
        }
      }
    }
  }
}
