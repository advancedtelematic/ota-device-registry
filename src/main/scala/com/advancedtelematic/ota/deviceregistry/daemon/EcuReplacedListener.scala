package com.advancedtelematic.ota.deviceregistry.daemon

import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.EcuReplaced
import com.advancedtelematic.ota.deviceregistry.db.EcuReplacementRepository
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class EcuReplacedListener()(implicit db: Database, ec: ExecutionContext) extends MsgOperation[EcuReplaced] {
  override def apply(msg: EcuReplaced): Future[Unit] =
    db.run(EcuReplacementRepository.insert(msg))
}
