/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry


import akka.actor.Scheduler
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.advancedtelematic.libats.auth.NamespaceDirectives
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.http.tracing.NullServerRequestTracing
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.data.{DeviceGenerators, GroupGenerators, PackageIdGenerators, SimpleJsonGenerator}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec, Suite}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

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
    with SimpleJsonGenerator with ServiceHttpClientSupport { // TODO: Use mock

  self: Suite =>

  implicit val routeTimeout: RouteTestTimeout =
    RouteTestTimeout(10.second)

  lazy val defaultNs: Namespace = Namespace("default")

  lazy val namespaceExtractor = NamespaceDirectives.defaultNamespaceExtractor

  private val namespaceAuthorizer = AllowUUIDPath.deviceUUID(namespaceExtractor, deviceAllowed)

  private def deviceAllowed(deviceId: DeviceId): Future[Namespace] =
    db.run(DeviceRepository.deviceNamespace(deviceId))

  lazy val messageBus = MessageBus.publisher(system, system.settings.config)

  implicit val tracing = new NullServerRequestTracing

  implicit val scheduler: Scheduler = system.scheduler

  // Route
  lazy implicit val route: Route =
    new DeviceRegistryRoutes(namespaceExtractor, namespaceAuthorizer, messageBus).route
}

trait ResourcePropSpec extends PropSpec with ResourceSpec with ScalaCheckPropertyChecks
