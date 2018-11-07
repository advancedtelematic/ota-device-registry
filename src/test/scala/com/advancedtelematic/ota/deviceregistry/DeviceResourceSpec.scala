/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import java.time.temporal.ChronoUnit
import java.time.{Instant, OffsetDateTime}

import akka.http.scaladsl.model.StatusCodes._
import cats.syntax.option._
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{ErrorRepresentation, PaginationResult}
import com.advancedtelematic.libats.http.monitoring.MetricsSupport
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceSeen
import com.advancedtelematic.ota.deviceregistry.common.{Errors, PackageStat}
import com.advancedtelematic.ota.deviceregistry.daemon.{DeleteDeviceHandler, DeviceSeenListener}
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, ValidExpression}
import com.advancedtelematic.ota.deviceregistry.data.{Device, DeviceStatus, PackageId, _}
import com.advancedtelematic.ota.deviceregistry.db.InstalledPackages
import com.advancedtelematic.ota.deviceregistry.db.InstalledPackages.{DevicesCount, InstalledPackage}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineMV
import eu.timepit.refined.string.Regex
import io.circe.generic.auto._
import org.scalacheck.Arbitrary._
import org.scalacheck.{Gen, Shrink}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

/**
  * Spec for DeviceRepository REST actions
  */
class DeviceResourceSpec extends ResourcePropSpec with ScalaFutures with Eventually {

  import Device._
  import GeneratorOps._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private implicit val exec = system.dispatcher
  private val publisher     = DeviceSeenListener.action(MessageBusPublisher.ignore)(_)

  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(15, Millis))

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  def isRecent(time: Option[Instant]): Boolean = time match {
    case Some(t) => t.isAfter(Instant.now.minus(3, ChronoUnit.MINUTES))
    case None    => false
  }

  private def sendDeviceSeen(uuid: DeviceId, lastSeen: Instant = Instant.now()): Unit =
    publisher(DeviceSeen(defaultNs, uuid, Instant.now())).futureValue

  private def createGroupedAndUngroupedDevices(): Map[String, Seq[DeviceId]] = {
    val deviceTs = genConflictFreeDeviceTs(12).sample.get
    val deviceIds    = deviceTs.map(createDeviceOk)
    val staticGroup = createStaticGroupOk()

    deviceIds.take(4).foreach(addDeviceToGroupOk(staticGroup, _))
    val expr = deviceTs.slice(4, 8).map(_.deviceId.underlying.take(6)).map(n => s"deviceid contains $n").reduce(_ + " or " + _)
    createDynamicGroupOk(Refined.unsafeApply(expr))

    Map("all" -> deviceIds, "groupedStatic" -> deviceIds.take(4),
      "groupedDynamic" -> deviceIds.slice(4, 8), "ungrouped" -> deviceIds.drop(8))
  }

  property("GET, PUT, DELETE, and POST '/ping' request fails on non-existent device") {
    forAll { (uuid: DeviceId, device: DeviceT) =>
      fetchDevice(uuid) ~> route ~> check { status shouldBe NotFound }
      updateDevice(uuid, device) ~> route ~> check { status shouldBe NotFound }
      deleteDevice(uuid) ~> route ~> check { status shouldBe NotFound }
    }
  }

  property("fetches only devices for the given namespace") {
    forAll { (dt1: DeviceT, dt2: DeviceT) =>
      val d1 = createDeviceInNamespaceOk(dt1, defaultNs)
      val d2 = createDeviceInNamespaceOk(dt2, Namespace("test-namespace"))

      listDevices() ~> route ~> check {
        status shouldBe OK
        val devices = responseAs[PaginationResult[Device]].values.map(_.uuid)
        devices should contain(d1)
        devices should not contain d2
      }
    }
  }

  property("GET request (for Id) after POST yields same device") {
    forAll { devicePre: DeviceT =>
      val uuid: DeviceId = createDeviceOk(devicePre)

      fetchDevice(uuid) ~> route ~> check {
        status shouldBe OK
        val devicePost: Device = responseAs[Device]
        devicePost.deviceId shouldBe devicePre.deviceId
        devicePost.deviceType shouldBe devicePre.deviceType
        devicePost.lastSeen shouldBe None
      }
    }
  }

  property("GET request with ?deviceId after creating yields same device.") {
    forAll { (deviceId: DeviceOemId, devicePre: DeviceT) =>
      val uuid = createDeviceOk(devicePre.copy(deviceId = deviceId))
      fetchByDeviceId(deviceId) ~> route ~> check {
        status shouldBe OK
        val devicePost1: Device = responseAs[PaginationResult[Device]].values.head
        fetchDevice(uuid) ~> route ~> check {
          status shouldBe OK
          val devicePost2: Device = responseAs[Device]

          devicePost1 shouldBe devicePost2
        }
      }
    }
  }

  property("PUT request after POST succeeds with updated device.") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1, d2) =>
        val uuid: DeviceId = createDeviceOk(d1)

        updateDevice(uuid, d2) ~> route ~> check {
          status shouldBe OK
          fetchDevice(uuid) ~> route ~> check {
            status shouldBe OK
            val devicePost: Device = responseAs[Device]
            devicePost.uuid shouldBe uuid
            devicePost.deviceId shouldBe d1.deviceId
            devicePost.deviceType shouldBe d1.deviceType
            devicePost.lastSeen shouldBe None
            devicePost.deviceName shouldBe d2.deviceName
          }
        }
    }
  }

  property("POST request creates a new device.") {
    forAll { devicePre: DeviceT =>
      val uuid = createDeviceOk(devicePre)
      fetchDevice(uuid) ~> route ~> check {
        status shouldBe OK
        val devicePost: Device = responseAs[Device]
        devicePost.uuid shouldBe uuid
        devicePost.deviceId shouldBe devicePre.deviceId
        devicePost.deviceType shouldBe devicePre.deviceType
      }
    }
  }

  property("POST request on 'ping' should update 'lastSeen' field for device.") {
    forAll { devicePre: DeviceT =>
      val uuid: DeviceId = createDeviceOk(devicePre)

      sendDeviceSeen(uuid)

      fetchDevice(uuid) ~> route ~> check {
        val devicePost: Device = responseAs[Device]

        devicePost.lastSeen should not be None
        isRecent(devicePost.lastSeen) shouldBe true
        devicePost.deviceStatus should not be DeviceStatus.NotSeen
      }
    }
  }

  property("POST request with same deviceName fails with conflict.") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1, d2) =>
        val name       = arbitrary[DeviceName].sample.get
        createDeviceOk(d1.copy(deviceName = name))

        createDevice(d2.copy(deviceName = name)) ~> route ~> check {
          status shouldBe Conflict
        }
    }
  }

  property("POST request with same deviceId fails with conflict.") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1, d2) =>
        createDeviceOk(d1)
        createDevice(d2.copy(deviceId = d1.deviceId)) ~> route ~> check {
          status shouldBe Conflict
        }
    }
  }

  property("First POST request on 'ping' should update 'activatedAt' field for device.") {
    forAll { devicePre: DeviceT =>
      val uuid = createDeviceOk(devicePre)

      sendDeviceSeen(uuid)

      fetchDevice(uuid) ~> route ~> check {
        val firstDevice = responseAs[Device]

        val firstActivation = firstDevice.activatedAt
        firstActivation should not be None
        isRecent(firstActivation) shouldBe true

        fetchDevice(uuid) ~> route ~> check {
          val secondDevice = responseAs[Device]

          secondDevice.activatedAt shouldBe firstActivation
        }
      }
    }
  }

  property("POST request on ping gets counted") {
    forAll { devicePre: DeviceT =>
      val start      = OffsetDateTime.now()
      val uuid = createDeviceOk(devicePre)
      val end        = start.plusHours(1)

      sendDeviceSeen(uuid)

      getActiveDeviceCount(start, end) ~> route ~> check {
        responseAs[ActiveDeviceCount].deviceCount shouldBe 1
      }
    }
  }

  property("PUT request updates device.") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1: DeviceT, d2: DeviceT) =>
        val uuid = createDeviceOk(d1)

        updateDevice(uuid, d2) ~> route ~> check {
          status shouldBe OK
          fetchDevice(uuid) ~> route ~> check {
            status shouldBe OK
            val updatedDevice: Device = responseAs[Device]
            updatedDevice.deviceId shouldBe d1.deviceId
            updatedDevice.deviceType shouldBe d1.deviceType
            updatedDevice.lastSeen shouldBe None
          }
        }
    }
  }

  property("PUT request does not update last seen") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1: DeviceT, d2: DeviceT) =>
        val uuid = createDeviceOk(d1)

        sendDeviceSeen(uuid)

        updateDevice(uuid, d2) ~> route ~> check {
          status shouldBe OK
          fetchDevice(uuid) ~> route ~> check {
            status shouldBe OK
            val updatedDevice: Device = responseAs[Device]
            updatedDevice.lastSeen shouldBe defined
          }
        }
    }
  }

  property("PUT request with same deviceName fails with conflict.") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1, d2) =>
        val uuid1 = createDeviceOk(d1)
        val _ = createDeviceOk(d2)

        updateDevice(uuid1, d1.copy(deviceName = d2.deviceName)) ~> route ~> check {
          status shouldBe Conflict
        }
    }
  }

  private[this] implicit val InstalledPackageDecoderInstance = {
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    io.circe.generic.semiauto.deriveDecoder[InstalledPackage]
  }

  property("Can install packages on a device") {
    forAll { (device: DeviceT, pkg: PackageId) =>
      val uuid = createDeviceOk(device)

      installSoftware(uuid, Set(pkg)) ~> route ~> check {
        status shouldBe NoContent
      }

      listPackages(uuid) ~> route ~> check {
        status shouldBe OK
        val response = responseAs[PaginationResult[InstalledPackage]]
        response.total shouldBe 1
        response.values.head.packageId shouldEqual pkg
        response.values.head.device shouldBe uuid
      }
    }
  }

  property("Can filter list of installed packages on a device") {
    val uuid = createDeviceOk(genDeviceT.generate)
    val pkgs = List(PackageId("foo", "1.0.0"), PackageId("bar", "1.0.0"))

    installSoftware(uuid, pkgs.toSet) ~> route ~> check {
      status shouldBe NoContent
    }

    listPackages(uuid, Some("foo")) ~> route ~> check {
      status shouldBe OK
      val response = responseAs[PaginationResult[InstalledPackage]]
      response.total shouldBe 1
      response.values.head.packageId shouldEqual pkgs.head
      response.values.head.device shouldBe uuid
    }
  }

  property("Can get stats for a package") {
    val deviceNumber = 20
    val groupNumber  = 5
    val deviceTs     = genConflictFreeDeviceTs(deviceNumber).sample.get
    val groups       = Gen.listOfN(groupNumber, genGroupName()).sample.get
    val pkg          = genPackageId.sample.get

    val deviceIds = deviceTs.map(createDeviceOk)
    val groupIds = groups.map(createStaticGroupOk)

    (0 until deviceNumber).foreach { i =>
      addDeviceToGroupOk(groupIds(i % groupNumber), deviceIds(i))
    }
    deviceIds.foreach(device => installSoftwareOk(device, Set(pkg)))

    getStatsForPackage(pkg) ~> route ~> check {
      status shouldBe OK
      val resp = responseAs[DevicesCount]
      resp.deviceCount shouldBe deviceNumber
      //convert to sets as order isn't important
      resp.groupIds shouldBe groupIds.toSet
    }
  }

  property("can list devices with custom pagination limit") {
    val limit                = 30
    val deviceTs             = genConflictFreeDeviceTs(limit * 2).sample.get
    deviceTs.foreach(createDeviceOk)

    searchDevice("", limit = limit) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]]
      result.values.length shouldBe limit
    }
  }

  property("can list devices with custom pagination limit and offset") {
    val limit                = 30
    val offset               = 10
    val deviceTs             = genConflictFreeDeviceTs(limit * 2).sample.get
    deviceTs.foreach(createDeviceOk)

    searchDevice("", offset = offset, limit = limit) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices.length shouldBe limit
      devices.zip(devices.tail).foreach {
        case (device1, device2) =>
          device1.deviceName.value.compareTo(device2.deviceName.value) should be <= 0
      }
    }
  }

  property("searching a device by 'regex' and 'deviceId' fails") {
    val deviceT = genDeviceT.sample.get
    createDeviceOk(deviceT)

    fetchByDeviceId(deviceT.deviceId, Some(""), None) ~> route ~> check {
      status shouldBe BadRequest
      responseAs[ErrorRepresentation].description should include ("regex must be empty when searching by deviceId")
    }
  }

  property("searching a device by 'groupId' and 'deviceId' fails") {
    val deviceT = genDeviceT.sample.get
    createDeviceOk(deviceT)

    fetchByDeviceId(deviceT.deviceId, None, Some(genStaticGroup.sample.get.id)) ~> route ~> check {
      status shouldBe BadRequest
      responseAs[ErrorRepresentation].description should include ("groupId must be empty when searching by deviceId")
    }
  }

  property("can list devices by group ID") {
    val limit                = 30
    val offset               = 10
    val deviceNumber         = 50
    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds = deviceTs.map(createDeviceOk)
    val groupId              = createStaticGroupOk()

    deviceIds.foreach { id =>
      addDeviceToGroupOk(groupId, id)
    }

    // test that we get back all the devices
    fetchByGroupId(groupId, offset = 0, limit = deviceNumber) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]]
      devices.total shouldBe deviceNumber
      devices.values.map(_.uuid).toSet shouldBe deviceIds.toSet
    }

    // test that the limit works
    fetchByGroupId(groupId, offset = offset, limit = limit) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]]
      devices.values.length shouldBe limit
    }
  }

  property("can list ungrouped devices") {
    val deviceNumber         = 50
    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds = deviceTs.map(createDeviceOk)

    val beforeGrouping = fetchUngrouped(offset = 0, limit = deviceNumber) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[Device]]
    }

    // add devices to group and check that we get less ungrouped devices
    val groupId = createStaticGroupOk()

    deviceIds.foreach { id =>
      addDeviceToGroupOk(groupId, id)
    }

    val afterGrouping = fetchUngrouped(offset = 0, limit = deviceNumber) ~> route ~> check {
      status shouldBe OK
      responseAs[PaginationResult[Device]]
    }

    beforeGrouping.total shouldBe afterGrouping.total + deviceNumber
  }

  property("can search static group devices") {
    val deviceT     = genDeviceT.generate
    val deviceUuid1 = createDeviceOk(deviceT)
    val deviceUuid2 = createDeviceOk(genDeviceT.generate)
    val group       = createStaticGroupOk()
    addDeviceToGroupOk(group, deviceUuid1)
    addDeviceToGroupOk(group, deviceUuid2)

    val regex = Refined.unsafeApply[String, Regex](deviceT.deviceName.value.substring(0, 10))
    getDevicesByGrouping(grouped = true, GroupType.static.some, regex.some) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid)
      result should contain(deviceUuid1)
      result shouldNot contain(deviceUuid2)
    }
  }

  property("can search dynamic group devices") {
    val deviceT1    = genDeviceTWith(Gen.const(refineMV[ValidDeviceName]("d1-xxyy-1234")), Gen.const(DeviceOemId("d1-xxyy-1234"))).generate
    val deviceT2    = genDeviceTWith(Gen.const(refineMV[ValidDeviceName]("d2-xxyy-5678")), Gen.const(DeviceOemId("d2-xxyy-5678"))).generate
    val deviceUuid1 = createDeviceOk(deviceT1)
    val deviceUuid2 = createDeviceOk(deviceT2)
    createDynamicGroupOk(refineMV[ValidExpression]("deviceid contains xxyy"))

    val regex = refineMV[Regex]("1234")
    getDevicesByGrouping(grouped = true, GroupType.dynamic.some, regex.some) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid)
      result should contain(deviceUuid1)
      result shouldNot contain(deviceUuid2)
    }
  }

  property("can list installed packages for all devices with custom pagination limit and offset") {

    val limit  = 30
    val offset = 10

    val deviceTs             = genConflictFreeDeviceTs(limit * 2).generate
    val deviceIds = deviceTs.map(createDeviceOk)

    // the database is case-insensitve so when we need to take that in to account when sorting in scala
    // furthermore PackageId is not lexicographically ordered so we just use pairs
    def canonPkg(pkg: PackageId) =
      (pkg.name.toLowerCase, pkg.version)

    val commonPkg = genPackageId.generate

    // get packages directly through the DB without pagination
    val beforePkgsAction = InstalledPackages.getInstalledForAllDevices(defaultNs)
    val beforePkgs       = db.run(beforePkgsAction).futureValue.map(canonPkg)

    val allDevicesPackages = deviceIds.map { device =>
      val pkgs = Gen.listOfN(2, genPackageId).generate.toSet + commonPkg
      installSoftwareOk(device, pkgs)
      pkgs
    }

    val allPackages =
      (allDevicesPackages.map(_.map(canonPkg)).toSet.flatten ++ beforePkgs.toSet).toSeq.sorted

    getInstalledForAllDevices(offset = offset, limit = limit) ~> route ~> check {
      status shouldBe OK
      val paginationResult = responseAs[PaginationResult[PackageId]]
      paginationResult.total shouldBe allPackages.length
      paginationResult.limit shouldBe limit
      paginationResult.offset shouldBe offset
      val packages = paginationResult.values.map(canonPkg)
      packages.length shouldBe scala.math.min(limit, allPackages.length)
      packages shouldBe sorted
      packages shouldBe allPackages.slice(offset, offset + limit)
    }
  }

  property("Posting to affected packages returns affected devices") {
    forAll { (device: DeviceT, p: PackageId) =>
      val uuid = createDeviceOk(device)

      installSoftwareOk(uuid, Set(p))

      getAffected(Set(p)) ~> route ~> check {
        status shouldBe OK
        responseAs[Map[DeviceId, Seq[PackageId]]].apply(uuid) shouldBe Seq(p)
      }
    }
  }

  property("Package stats correct reports number of installed instances") {
    val devices    = genConflictFreeDeviceTs(10).sample.get
    val pkgName    = genPackageIdName.sample.get
    val pkgVersion = genConflictFreePackageIdVersion(2)

    val uuids = devices.map(createDeviceOk(_))
    uuids.zipWithIndex.foreach {
      case (uuid, i) =>
        if (i % 2 == 0) {
          installSoftwareOk(uuid, Set(PackageId(pkgName, pkgVersion.head)))
        } else {
          installSoftwareOk(uuid, Set(PackageId(pkgName, pkgVersion(1))))
        }
    }
    getPackageStats(pkgName) ~> route ~> check {
      status shouldBe OK
      val r = responseAs[PaginationResult[PackageStat]]
      r.total shouldBe 2
      r.values.contains(PackageStat(pkgVersion.head, 5)) shouldBe true
      r.values.contains(PackageStat(pkgVersion(1), 5)) shouldBe true
    }
  }

  property("DELETE existing device returns 202") {
    forAll { devicePre: DeviceT =>
      val uuid = createDeviceOk(devicePre)

      deleteDevice(uuid) ~> route ~> check {
        status shouldBe Accepted
      }
    }
  }

  new DeleteDeviceHandler(system.settings.config, db, MetricsSupport.metricRegistry).start()

  property("DELETE device removes it from its group") {
    forAll { (devicePre: DeviceT, groupName: Group.Name) =>
      val uuid = createDeviceOk(devicePre)
      val groupId    = createStaticGroupOk(groupName)

      addDeviceToGroupOk(groupId, uuid)
      listDevicesInGroup(groupId) ~> route ~> check {
        status shouldBe OK
        val devices = responseAs[PaginationResult[DeviceId]]
        devices.values.find(_ == uuid) shouldBe Some(uuid)
      }

      deleteDevice(uuid) ~> route ~> check {
        status shouldBe Accepted
      }

      import org.scalatest.time.SpanSugar._
      eventually(timeout(5.seconds), interval(100.millis)) {
        fetchByGroupId(groupId, offset = 0, limit = 10) ~> route ~> check {
          status shouldBe OK
          val devices = responseAs[PaginationResult[Device]]
          devices.values.find(_.uuid == uuid) shouldBe None
        }
      }

      listDevicesInGroup(groupId) ~> route ~> check {
        status shouldBe OK
        val devices = responseAs[PaginationResult[DeviceId]]
        devices.values.find(_ == uuid) shouldBe None
      }
    }
  }

  property("DELETE device removes it from all groups") {
    val deviceNumber         = 50
    val deviceTs             = genConflictFreeDeviceTs(deviceNumber).sample.get
    val deviceIds = deviceTs.map(createDeviceOk)

    val groupNumber            = 10
    val groups                 = Gen.listOfN(groupNumber, genGroupName()).sample.get
    val groupIds = groups.map(createStaticGroupOk)

    (0 until deviceNumber).foreach { i =>
      addDeviceToGroupOk(groupIds(i % groupNumber), deviceIds(i))
    }

    val uuid: DeviceId = deviceIds.head
    deleteDevice(uuid) ~> route ~> check {
      status shouldBe Accepted
    }

    import org.scalatest.time.SpanSugar._
    eventually(timeout(5.seconds), interval(100.millis)) {
      (0 until groupNumber).foreach { i =>
        fetchByGroupId(groupIds(i), offset = 0, limit = deviceNumber) ~> route ~> check {
          status shouldBe OK
          val devices = responseAs[PaginationResult[Device]]
          devices.values.find(_.uuid == uuid) shouldBe None
        }
      }
    }
  }

  property("DELETE device does not cause error on subsequent DeviceSeen events") {
    forAll(genConflictFreeDeviceTs(2)) {
      case Seq(d1, d2) =>
        val uuid1 = createDeviceOk(d1)
        val uuid2 = createDeviceOk(d2)

        deleteDevice(uuid1) ~> route ~> check {
          status shouldBe Accepted
        }

        sendDeviceSeen(uuid1)
        sendDeviceSeen(uuid2)
        fetchDevice(uuid2) ~> route ~> check {
          val devicePost: Device = responseAs[Device]
          devicePost.lastSeen should not be None
          isRecent(devicePost.lastSeen) shouldBe true
          devicePost.deviceStatus should not be DeviceStatus.NotSeen
        }
    }
  }

  property("getting the groups of a device returns the correct static groups") {
    val groupId1   = createStaticGroupOk()
    val groupId2   = createStaticGroupOk()
    val deviceUuid = createDeviceOk(genDeviceT.sample.get)

    addDeviceToGroup(groupId1, deviceUuid) ~> route ~> check {
      status shouldBe OK
    }
    addDeviceToGroup(groupId2, deviceUuid) ~> route ~> check {
      status shouldBe OK
    }

    getGroupsOfDevice(deviceUuid) ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[PaginationResult[GroupId]]
      groups.total should be(2)
      groups.values should contain(groupId1)
      groups.values should contain(groupId2)
    }
  }

  property("counts devices that satisfy a dynamic group expression") {
    val testDevices = Map(
      Refined.unsafeApply[String, ValidDeviceName]("device1") -> DeviceOemId("abc123"),
      Refined.unsafeApply[String, ValidDeviceName]("device2") -> DeviceOemId("123abc456"),
      Refined.unsafeApply[String, ValidDeviceName]("device3") -> DeviceOemId("123aba456")
    )
    testDevices
      .map(t => (Gen.const(t._1), Gen.const(t._2)))
      .map((genDeviceTWith _).tupled(_))
      .map(_.sample.get)
      .map(createDeviceOk)

    val expression: GroupExpression = Refined.unsafeApply("deviceid contains abc and deviceid position(5) is b")
    countDevicesForExpression(expression.some) ~> route ~> check {
      status shouldBe OK
      responseAs[Int] shouldBe 1
    }
  }

  property("counting devices that satisfy a dynamic group expression fails if no expression is given") {
    countDevicesForExpression(None) ~> route ~> check {
      status shouldBe BadRequest
      responseAs[ErrorRepresentation].code shouldBe Errors.Codes.InvalidGroupExpression
    }
  }

  property("finds all (and only) grouped devices") {
    val m = createGroupedAndUngroupedDevices()

    getDevicesByGrouping(grouped = true, None) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(m("all").contains)
      result should contain theSameElementsAs m("groupedStatic") ++ m("groupedDynamic")
    }
  }

  property("finds all (and only) static grouped devices") {
    val m = createGroupedAndUngroupedDevices()

    getDevicesByGrouping(grouped = true, Some(GroupType.static)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(m("all").contains)
      result should contain theSameElementsAs m("groupedStatic")
    }
  }

  property("finds all (and only) dynamic grouped devices") {
    val m = createGroupedAndUngroupedDevices()

    getDevicesByGrouping(grouped = true, Some(GroupType.dynamic)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(m("all").contains)
      result should contain theSameElementsAs m("groupedDynamic")
    }
  }

  property("finds all (and only) ungrouped devices") {
    val m = createGroupedAndUngroupedDevices()

    getDevicesByGrouping(grouped = false, None) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(m("all").contains)
      result should contain theSameElementsAs m("ungrouped")
    }
  }

  property("finds all (and only) non-static grouped devices") {
    val m = createGroupedAndUngroupedDevices()

    getDevicesByGrouping(grouped = false, Some(GroupType.static)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(m("all").contains)
      result should contain theSameElementsAs m("all").filterNot(m("groupedStatic").contains)
    }
  }

  property("finds all (and only) non-dynamic grouped devices") {
    val m = createGroupedAndUngroupedDevices()

    getDevicesByGrouping(grouped = false, Some(GroupType.dynamic)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(m("all").contains)
      result should contain theSameElementsAs m("all").filterNot(m("groupedDynamic").contains)
    }
  }

  property("finds static group devices only once") {
    val deviceUuid = createDeviceOk(genDeviceT.generate)
    val group1 = createStaticGroupOk()
    val group2 = createStaticGroupOk()
    addDeviceToGroupOk(group1, deviceUuid)
    addDeviceToGroupOk(group2, deviceUuid)

    getDevicesByGrouping(grouped = true, Some(GroupType.static)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(_ == deviceUuid)
      result.length shouldBe 1
    }
  }

  property("finds dynamic group devices only once") {
    val deviceUuid = createDeviceOk(genDeviceT.generate.copy(deviceId = DeviceOemId("abcd-1234")))
    createDynamicGroupOk(refineMV[ValidExpression]("deviceid contains abcd"))
    createDynamicGroupOk(refineMV[ValidExpression]("deviceid contains 1234"))

    getDevicesByGrouping(grouped = true, Some(GroupType.dynamic)) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(_ == deviceUuid)
      result.length shouldBe 1
    }
  }

  property("finds grouped devices only once") {
    val deviceUuid = createDeviceOk(genDeviceT.generate.copy(deviceId = DeviceOemId("abcd")))
    createDynamicGroupOk(refineMV[ValidExpression]("deviceid contains abcd"))
    val group = createStaticGroupOk()
    addDeviceToGroupOk(group, deviceUuid)

    getDevicesByGrouping(grouped = true, groupType = None) ~> route ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid).filter(_ == deviceUuid)
      result.length shouldBe 1
    }
  }

}
