package com.advancedtelematic.ota.api_provider.http

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{StatusCodes, Uri}
import com.advancedtelematic.libats.data.{EcuIdentifier, PaginationResult}
import com.advancedtelematic.ota.deviceregistry.{DeviceRequests, ResourceSpec}
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus
import org.scalatest.FunSuite
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._
import cats.syntax.show._
import cats.syntax.either._
import com.advancedtelematic.libats.data.DataType.{CampaignId, ValidChecksum}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId._
import com.advancedtelematic.libtuf.data.TufDataType.{ValidHardwareIdentifier, ValidTargetFilename}
import com.advancedtelematic.ota.api_provider.client.DirectorClient.{DirectorQueueItem, EcuInfoImage, Hashes}
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry
import cats.syntax.option._
import com.advancedtelematic.libats.messaging_datatype.DataType.{Event, EventType}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceEventMessage
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceEventListener
import com.advancedtelematic.ota.deviceregistry.data.DataType.IndexedEventType
import io.circe.syntax._
import org.scalatest.OptionValues._
import org.scalatest.LoneElement._

class DeviceInfoResourceSpec extends FunSuite with ResourceSpec with Eventually with DeviceRequests with ScalaFutures {

  import com.advancedtelematic.ota.api_provider.data.DataType._

  private val deviceEventListener = new DeviceEventListener()

  private def apiProviderUri(pathSuffixes: String*): Uri = {
    val BasePath = Path("/api-provider") / "api" / "v1alpha"
    Uri.Empty.withPath(pathSuffixes.foldLeft(BasePath)(_ / _))
  }

  test("list devices, paginated") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    createDeviceOk(device)

    Get(apiProviderUri("devices"))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val pages = responseAs[PaginationResult[ListingDevice]]

      pages.limit shouldBe 50
      pages.offset shouldBe 0
      pages.total shouldBe 1

      val first = pages.values.head

      first shouldBe ListingDevice(device.uuid.get, device.deviceId)
    }
  }

  test("list devices, paginated, filtered by oem id") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    createDeviceOk(device)

    val device02 = genDeviceT.retryUntil(_.uuid.isDefined).generate
    createDeviceOk(device02)

    Get(apiProviderUri("devices").withRawQueryString(s"deviceId=${device.deviceId.show}"))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val pages = responseAs[PaginationResult[ListingDevice]]

      pages.limit shouldBe 50
      pages.offset shouldBe 0
      pages.total shouldBe 1

      val first = pages.values.head

      first shouldBe ListingDevice(device.uuid.get, device.deviceId)
    }
  }


  test("gets information for a device") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    val ecuId = EcuIdentifier("somefakeid").valueOr(throw _)
    val hardwareIdentifier = "fakehwid".refineTry[ValidHardwareIdentifier].get
    val targetFilename = "some-hash".refineTry[ValidTargetFilename].get
    val hash = "848cba347e8a37330b97835936dd4f846291739d0d5efa9eb10c75e4c15ba87a".refineTry[ValidChecksum].get
    val image = EcuInfoImage(targetFilename, 2222, Hashes(hash))

    directorClient.addDevice(deviceId, ecuId, hardwareIdentifier, image)

    Get(apiProviderUri("devices", deviceId.show))  ~> route ~> check {
      status shouldBe StatusCodes.OK
      val apiDevice = responseAs[ApiDevice]
      apiDevice.deviceId shouldBe device.deviceId
      apiDevice.uuid shouldBe device.uuid.get
      apiDevice.lastSeen shouldBe None
      apiDevice.status shouldBe DeviceStatus.NotSeen

      apiDevice.primaryEcu shouldBe defined
      apiDevice.primaryEcu.map(_.ecuId) should contain(ecuId)
      apiDevice.primaryEcu.map(_.installedSoftwareVersion.filename) should contain(targetFilename)
      apiDevice.primaryEcu.map(_.installedSoftwareVersion.length) should contain(2222)
      apiDevice.primaryEcu.map(_.installedSoftwareVersion.hashes.head._2) should contain(hash)
    }
  }

  test("returns empty primary ecu info if director returns 404") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    Get(apiProviderUri("devices", deviceId.show)) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val apiDevice = responseAs[ApiDevice]
      apiDevice.deviceId shouldBe device.deviceId
      apiDevice.uuid shouldBe device.uuid.get
      apiDevice.lastSeen shouldBe None
      apiDevice.status shouldBe DeviceStatus.NotSeen

      apiDevice.primaryEcu shouldBe empty
    }
  }


  test("returns an empty device queue") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    Get(apiProviderUri("devices", deviceId.show, "queue")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val queue = responseAs[List[Unit]]
      queue shouldBe empty
    }
  }

  test("returns a non empty device queue") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    val ecuId = EcuIdentifier("somefakeid").valueOr(throw _)
    val correlationId = CampaignId(UUID.randomUUID())
    val targetFilename = "some-hash".refineTry[ValidTargetFilename].get
    val hash = "848cba347e8a37330b97835936dd4f846291739d0d5efa9eb10c75e4c15ba87a".refineTry[ValidChecksum].get
    val image = EcuInfoImage(targetFilename, 2222, Hashes(hash))

    directorClient.addQueueItem(deviceId, DirectorQueueItem(correlationId.some, Map(ecuId -> image)))

    Get(apiProviderUri("devices", deviceId.show, "queue")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val queue = responseAs[List[QueueItem]]
      queue should have size(1)

      queue.head.updateId shouldBe correlationId
      queue.head.deviceUuid shouldBe deviceId
      queue.head.ecus should contain key(ecuId)
      val (_, softwareVersion) = queue.head.ecus.head

      softwareVersion.filename shouldBe targetFilename
    }
  }

  test("returns an empty queue when correlation id in director is empty") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    directorClient.addQueueItem(deviceId, DirectorQueueItem(correlationId = None, Map.empty))

    Get(apiProviderUri("devices", deviceId.show, "queue")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val queue = responseAs[List[QueueItem]]
      queue shouldBe empty
    }
  }

  test("events includes events for a device") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)
    val ecuId = EcuIdentifier("somefakeid").valueOr(throw _)
    val campaignIdUuid = UUID.randomUUID()
    val campaignId = CampaignId(campaignIdUuid)
    val now = Instant.now()

    val payload = Map(
      "ecu" -> ecuId.asJson,
      "correlationId" -> campaignId.toString().asJson,
      "campaignId" -> campaignIdUuid.toString.asJson
    ).asJson

    val event01 = Event(deviceId, UUID.randomUUID().toString, EventType("campaign_accepted", 0), now, now, payload)
    deviceEventListener.apply(DeviceEventMessage(defaultNs, event01)).futureValue

    val event02 = Event(deviceId, UUID.randomUUID().toString, EventType("EcuInstallationStarted", 0), now.plusMillis(1), now.plusMillis(1), payload)
    deviceEventListener.apply(DeviceEventMessage(defaultNs, event02)).futureValue

    val event03 = Event(deviceId, UUID.randomUUID().toString, EventType("EcuInstallationCompleted", 0), now.plusMillis(2), now.plusMillis(2), payload)
    deviceEventListener.apply(DeviceEventMessage(defaultNs, event03)).futureValue

    Get(apiProviderUri("devices", deviceId.show, "events")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val updateStatus = responseAs[ApiDeviceEvents]

      updateStatus.deviceUuid shouldBe deviceId

      val events = updateStatus.events

      events should have size(3)

      println(events)

      events.headOption.value.updateId.value shouldBe campaignId
      events.headOption.value.ecuId.value shouldBe ecuId
      events.map(_.name) shouldBe Vector(IndexedEventType.EcuInstallationCompleted, IndexedEventType.EcuInstallationStarted, IndexedEventType.CampaignAccepted)
    }
  }

  test("returns events filtered by updateId") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)
    val ecuId = EcuIdentifier("somefakeid").valueOr(throw _)
        val now = Instant.now()

    val campaignId01 = CampaignId(UUID.randomUUID())
    val campaignId02 = CampaignId(UUID.randomUUID())

    val payload01 = Map(
      "ecu" -> ecuId.asJson,
      "correlationId" -> campaignId01.toString().asJson
    ).asJson

    val payload02 = Map(
      "ecu" -> ecuId.asJson,
      "correlationId" -> campaignId02.toString().asJson
    ).asJson

    val event01 = Event(deviceId, UUID.randomUUID().toString, EventType("EcuInstallationStarted", 0), now, now, payload01)
    deviceEventListener.apply(DeviceEventMessage(defaultNs, event01)).futureValue

    val event02 = Event(deviceId, UUID.randomUUID().toString, EventType("EcuInstallationCompleted", 0), now, now, payload02)
    deviceEventListener.apply(DeviceEventMessage(defaultNs, event02)).futureValue

    Get(apiProviderUri("devices", deviceId.show, "events").withQuery(Uri.Query("updateId" -> campaignId01.toString()))) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val updateStatus = responseAs[ApiDeviceEvents]

      updateStatus.deviceUuid shouldBe deviceId

      val events = updateStatus.events

      events should have size(1)

      events.headOption.value.updateId.value shouldBe campaignId01
      events.map(_.name).headOption.value shouldBe IndexedEventType.EcuInstallationStarted
    }
  }

  test("removes duplicates if director returns duplicate queue items") {
    val device = genDeviceT.retryUntil(_.uuid.isDefined).generate
    val deviceId = createDeviceOk(device)

    val ecuId = EcuIdentifier("somefakeid").valueOr(throw _)
    val correlationId = CampaignId(UUID.randomUUID())
    val targetFilename = "some-hash".refineTry[ValidTargetFilename].get
    val hash = "848cba347e8a37330b97835936dd4f846291739d0d5efa9eb10c75e4c15ba87a".refineTry[ValidChecksum].get
    val image = EcuInfoImage(targetFilename, 2222, Hashes(hash))

    directorClient.addQueueItem(deviceId, DirectorQueueItem(correlationId.some, Map(ecuId -> image)))
    directorClient.addQueueItem(deviceId, DirectorQueueItem(correlationId.some, Map(ecuId -> image)))

    Get(apiProviderUri("devices", deviceId.show, "queue")) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[List[QueueItem]].loneElement.updateId shouldBe correlationId
    }
  }
}
