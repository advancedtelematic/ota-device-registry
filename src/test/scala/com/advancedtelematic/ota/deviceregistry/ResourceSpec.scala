/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.advancedtelematic.libats.auth.AuthedNamespaceScope
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.{
  DeviceGenerators,
  GroupGenerators,
  PackageIdGenerators,
  SimpleJsonGenerator,
  Uuid,
  UuidGenerator
}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec, Suite}
import org.scalatest.prop.PropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration._

trait ResourceSpec
    extends ScalatestRouteTest
    with BeforeAndAfterAll
    with DatabaseSpec
    with DeviceGenerators
    with DeviceRequests
    with GroupGenerators
    with GroupRequests
    with PublicCredentialsRequests
    with PackageIdGenerators
    with Matchers
    with SimpleJsonGenerator
    with UuidGenerator {

  self: Suite =>

  implicit val routeTimeout: RouteTestTimeout =
    RouteTestTimeout(10.second)

  lazy val defaultNs: Namespace = Namespace("default")

  lazy val namespaceExtractor = Directives.provide(AuthedNamespaceScope(defaultNs))

  private val namespaceAuthorizer =
    UuidDirectives.allowExtractor(namespaceExtractor, UuidDirectives.extractUuid, deviceAllowed)

  private def deviceAllowed(deviceId: Uuid): Future[Namespace] =
    db.run(DeviceRepository.deviceNamespace(deviceId))

  lazy val messageBus =
    MessageBus.publisher(system, system.settings.config) match {
      case Right(v)  => v
      case Left(err) => throw err
    }

  // Route
  lazy implicit val route: Route =
    new DeviceRegistryRoutes(namespaceExtractor, namespaceAuthorizer, messageBus).route
}

trait ResourcePropSpec extends PropSpec with ResourceSpec with PropertyChecks
