package com.advancedtelematic.ota.deviceregistry.daemon

import akka.Done
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.db.DeviceRepository
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceUpdateCompletedEncoder
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateAssigned, DeviceUpdateCanceled, DeviceUpdateCompleted, DeviceUpdateEvent}
import com.advancedtelematic.libats.messaging_datatype.MessageLike
import com.advancedtelematic.ota.deviceregistry.db.InstallationReportRepository
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceUpdateStatus._
import MessageLike._
import io.circe.syntax._
import java.time.Instant

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.ota.deviceregistry.common.Errors
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}


  final case class DeviceUpdateStatus(namespace: Namespace,
                                      device: DeviceId,
                                      status: DeviceStatus,
                                      timestamp: Instant = Instant.now())

  object DeviceUpdateStatus {
    import cats.syntax.show._
    import com.advancedtelematic.libats.codecs.CirceCodecs._
    implicit val MessageLikeInstance = MessageLike.derive[DeviceUpdateStatus](_.device.show)
  }

class DeviceUpdateEventListener(messageBus: MessageBusPublisher)(implicit val db: Database, ec: ExecutionContext) extends (DeviceUpdateEvent => Future[Unit]) {

  private val _log = LoggerFactory.getLogger(this.getClass)

  override def apply(event: DeviceUpdateEvent): Future[Unit] = {
    handleEvent(event)
      .flatMap { setDeviceStatus(event.deviceUuid, _) }
      .recoverWith {
        case Errors.MissingDevice =>
          _log.warn(s"Device ${event.deviceUuid} does not exist ($event)")
          FastFuture.successful(())
      }
  }

  private def handleEvent(event: DeviceUpdateEvent): Future[DeviceStatus] = event match {
    case msg: DeviceUpdateAssigned  => Future.successful(DeviceStatus.Outdated)
    case msg: DeviceUpdateCanceled  => Future.successful(DeviceStatus.UpToDate)
    case msg: DeviceUpdateCompleted => db.run {
      InstallationReportRepository
        .saveInstallationResults(
          msg.correlationId, msg.deviceUuid, msg.result.code, msg.result.success, msg.ecuReports,
          msg.eventTime, msg.asJson)
        .map(_ => if (msg.result.success) DeviceStatus.UpToDate else DeviceStatus.Error)
    }
  }

  def setDeviceStatus(deviceUuid: DeviceId, deviceStatus: DeviceStatus) = {
    val f = for {
      device <- DeviceRepository.findByUuid(deviceUuid)
      _ <- DeviceRepository.setDeviceStatus(
        deviceUuid,
        if(device.lastSeen.isEmpty) DeviceStatus.NotSeen else deviceStatus)
    } yield (device, deviceStatus)

    db.run(f)
      .flatMap {
      case (device, status) =>
        messageBus
          .publish(DeviceUpdateStatus(device.namespace, device.uuid, status, Instant.now()))
    }.map(_ => ())
  }

}
