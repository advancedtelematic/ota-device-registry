package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.server.{Directive1, Directives, Route}
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.codecs.CirceCodecs._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.RefinedMarshallingSupport.refinedUnmarshaller
import com.advancedtelematic.libtuf.data.TufDataType.TargetFilename
import com.advancedtelematic.ota.deviceregistry.db.CurrentImageRepository
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext

class ImagesResource(namespaceExtractor: Directive1[AuthedNamespaceScope])
                    (implicit ec: ExecutionContext, db: Database) extends Directives {

  def findAffected(ns: Namespace): Route =
    parameters('filepath.as[TargetFilename], 'offset.as[Long].?, 'limit.as[Long].?) { (filepath, offset, limit) =>
      complete(db.run(CurrentImageRepository.findAffected(ns, filepath, offset, limit)))
    }

  def installedCount(ns: Namespace): Route =
    parameter('filepath.as[TargetFilename].*) { filepaths =>
      complete(db.run(CurrentImageRepository.countInstalledImages(ns, filepaths.toSeq)))
    }

  val route: Route = namespaceExtractor { ns =>
    pathPrefix("images") {
      val scope = Scopes.devices(ns)
      scope.get {
        path("affected") {
          findAffected(ns.namespace)
        } ~
        path("installed_count") {
          installedCount(ns.namespace)
        }
      }
    }
  }

}
