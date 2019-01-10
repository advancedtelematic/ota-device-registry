package com.advancedtelematic.ota.deviceregistry.util

import java.util.concurrent.ConcurrentHashMap

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.client.{AuditorClient, DeviceUpdateReport}

import scala.concurrent.Future

class FakeAuditorClient extends AuditorClient {

  private val reports = new ConcurrentHashMap[(Namespace, DeviceId), Seq[DeviceUpdateReport]]()

  def addDeviceUpdateReport(ns: Namespace, deviceId: DeviceId, newReports: DeviceUpdateReport*): Unit =
    reports.put(ns -> deviceId, newReports ++: reports.getOrDefault(ns -> deviceId, Seq.empty))

  override def getUpdateReports(ns: Namespace, deviceId: DeviceId): Future[Seq[DeviceUpdateReport]] =
    FastFuture.successful(reports.getOrDefault(ns -> deviceId, Seq.empty))
}
