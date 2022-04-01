package com.advancedtelematic.ota.deviceregistry.daemon

import akka.actor.Scheduler
import cats.syntax.show._
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.{EcuReplacement, EcuReplacementFailed}
import com.advancedtelematic.libats.slick.db.DatabaseHelper.DatabaseWithRetry
import com.advancedtelematic.ota.deviceregistry.common.Errors.MissingDevice
import com.advancedtelematic.ota.deviceregistry.data.DeviceStatus
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, EcuReplacementRepository}
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class EcuReplacementListener()(implicit db: Database, ec: ExecutionContext, scheduler: Scheduler) extends MsgOperation[EcuReplacement] {
  private val _log = LoggerFactory.getLogger(this.getClass)

  override def apply(msg: EcuReplacement): Future[Unit] = {
    val action = for {
      _ <- EcuReplacementRepository.insert(msg)
      _ <- msg match {
        case _: EcuReplacementFailed => DeviceRepository.setDeviceStatus(msg.deviceUuid, DeviceStatus.Error)
        case _ => DBIO.successful(())
      }
    } yield ()

    db.runWithRetry(action).recover {
      case MissingDevice =>
        _log.warn(s"Trying to replace ECUs on a non-existing or deleted device: ${msg.deviceUuid.show}.")
    }
  }
}
