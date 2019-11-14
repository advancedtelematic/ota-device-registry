package com.advancedtelematic.ota.api_provider.http

import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server.{Directives, Route}

class ApiDocsResource {
  import Directives._

  val route: Route = {
    pathPrefix("docs") {
      path("definition.yml") {
        getFromResource("api-definition.yml", ContentTypes.`text/plain(UTF-8)`)
      } ~
      pathEnd {
        getFromResource("swagger.html", ContentTypes.`text/html(UTF-8)`)
      }
    }
  }
}
