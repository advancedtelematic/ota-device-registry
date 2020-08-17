package com.advancedtelematic.ota.deviceregistry.daemon

import com.advancedtelematic.libats.http.Errors.RawError
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.EcuReplaced
import com.advancedtelematic.ota.deviceregistry.common.Errors.Codes
import com.advancedtelematic.ota.deviceregistry.db.EcuReplacementRepository
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class EcuReplacedListener()(implicit db: Database, ec: ExecutionContext) extends MsgOperation[EcuReplaced] {
  private val _log = LoggerFactory.getLogger(this.getClass)

  override def apply(msg: EcuReplaced): Future[Unit] =
    db.run(EcuReplacementRepository.insert(msg)).recover {
      case RawError(Codes.EcuRepeatedReplacement, _, desc, errorId) =>
        _log.warn(s"EcuRepeatedReplacement error $errorId: $desc")
    }
}
