/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.http.scaladsl.util.FastFuture
import cats.instances.queue
import com.advancedtelematic.libats.auth.NamespaceDirectives
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.http.ServiceHttpClientSupport
import com.advancedtelematic.libats.http.tracing.NullServerRequestTracing
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.libtuf.data.TufDataType.HardwareIdentifier
import com.advancedtelematic.ota.api_provider.client.DirectorClient._
import com.advancedtelematic.ota.api_provider.client.DirectorClient
import com.advancedtelematic.ota.deviceregistry.data.{DeviceGenerators, GroupGenerators, PackageIdGenerators, SimpleJsonGenerator}
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec, Suite}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._

class MockDirectorClient() extends DirectorClient {
  private val devices = new ConcurrentHashMap[DeviceId, EcuInfoResponse]()
  private val queues =  new ConcurrentHashMap[DeviceId, Vector[DirectorQueueItem]]()

  def addDevice(deviceId: DeviceId, ecuIdentifier: EcuIdentifier, hardwareId: HardwareIdentifier, image: EcuInfoImage): Unit = {
    val ecuInfoResponse = EcuInfoResponse(ecuIdentifier, hardwareId, primary = true, image)
    devices.put(deviceId, ecuInfoResponse)
  }

  def addQueueItem(deviceId: DeviceId, queueItem: DirectorQueueItem): Unit = {
    queues.compute(deviceId, (_, oldQueue) =>{
      Option(oldQueue).toVector.flatten :+ queueItem
    })
  }

  override def fetchDeviceEcus(ns: Namespace, deviceId: DeviceId): Future[Seq[DirectorClient.EcuInfoResponse]] = FastFuture.successful {
    Option(devices.get(deviceId)).toSeq
  }

  override def fetchDeviceQueue(ns: Namespace, deviceId: DeviceId): Future[Seq[DirectorQueueItem]] = FastFuture.successful {
    Option(queues.get(deviceId)).toList.flatten
  }
}

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

  protected val directorClient = new MockDirectorClient

  // Route
  lazy implicit val route: Route =
    new DeviceRegistryRoutes(namespaceExtractor, namespaceAuthorizer, messageBus, directorClient).route
}

trait ResourcePropSpec extends PropSpec with ResourceSpec with ScalaCheckPropertyChecks
