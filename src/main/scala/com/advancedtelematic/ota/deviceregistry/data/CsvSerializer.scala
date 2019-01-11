package com.advancedtelematic.ota.deviceregistry.data

import akka.util.ByteString
import cats.syntax.show._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceOemId

trait CsvSerializer[T] {
  def toCsvRow(value: T): Seq[String]
}

object CsvSerializer {
  val fieldSeparator = ";"
  val recordSeparator = "\n"

  implicit val deviceFailureSerializer: CsvSerializer[(DeviceId, DeviceOemId, String)] =
    (a: (DeviceId, DeviceOemId, String)) => Seq(a._1.show, a._2.show, a._3)

  def header(header: String*): ByteString = ByteString(header.mkString("", fieldSeparator, recordSeparator))

  def asCsvRow[T: CsvSerializer](row: T)(implicit serializer: CsvSerializer[T]): ByteString =
    ByteString(serializer.toCsvRow(row).mkString("", fieldSeparator, recordSeparator))

}
