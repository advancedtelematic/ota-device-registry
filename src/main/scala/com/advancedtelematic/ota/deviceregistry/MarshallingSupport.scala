package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import cats.syntax.either._
import com.advancedtelematic.libats.data.DataType.CorrelationId
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.ota.deviceregistry.data.DataType.InstallationStatsLevel
import com.advancedtelematic.ota.deviceregistry.data.DataType.InstallationStatsLevel.InstallationStatsLevel
import com.advancedtelematic.ota.deviceregistry.data.Group.GroupId
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.SortBy.SortBy
import com.advancedtelematic.ota.deviceregistry.data._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._


object MarshallingSupport {

  implicit val groupIdUnmarshaller: Unmarshaller[String, GroupId] = GroupId.unmarshaller

  implicit val correlationIdUnmarshaller: FromStringUnmarshaller[CorrelationId] = Unmarshaller.strict {
    CorrelationId.fromString(_).leftMap(new IllegalArgumentException(_)).valueOr(throw _)
  }

  implicit val groupExpressionUnmarshaller: FromStringUnmarshaller[GroupExpression] = Unmarshaller.strict(GroupExpression(_).valueOr(throw _))

  implicit val installationStatsLevelUnmarshaller: FromStringUnmarshaller[InstallationStatsLevel] =
    Unmarshaller.strict {
      _.toLowerCase match {
        case "device" => InstallationStatsLevel.Device
        case "ecu"    => InstallationStatsLevel.Ecu
        case s        => throw new IllegalArgumentException(s"Invalid value for installation stats level parameter: $s.")
      }
    }

  val installationFailureMarshaller = implicitly[ToResponseMarshaller[Seq[(DeviceOemId, String)]]]

  implicit val installationFailureCsvMarshaller: ToResponseMarshaller[Seq[(DeviceOemId, String, String)]] =
    Marshaller.withFixedContentType(ContentTypes.`text/csv(UTF-8)`) { t =>
      val csv = CsvSerializer.asCsv(Seq("Device ID", "Failure Code", "Failure Description"), t)
      val e = HttpEntity(ContentTypes.`text/csv(UTF-8)`, csv)
      val h = `Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> "device-failures.csv"))
      HttpResponse(headers = h :: Nil, entity = e)
    }

  implicit val groupTypeUnmarshaller: FromStringUnmarshaller[GroupType] = Unmarshaller.strict(GroupType.withName)
  implicit val groupNameUnmarshaller: FromStringUnmarshaller[GroupName] = Unmarshaller.strict(GroupName(_).valueOr(throw _))

  implicit val sortByUnmarshaller: FromStringUnmarshaller[SortBy] = Unmarshaller.strict {
    _.toLowerCase match {
      case "name"      => SortBy.Name
      case "createdat" => SortBy.CreatedAt
      case s           => throw new IllegalArgumentException(s"Invalid value for sorting parameter: '$s'.")
    }
  }

}
