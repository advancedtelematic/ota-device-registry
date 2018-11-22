package com.advancedtelematic.ota.deviceregistry.db

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.RawHeader
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.test.DatabaseSpec
import com.advancedtelematic.ota.deviceregistry.client.SotaCoreBlacklistedPackage
import com.advancedtelematic.ota.deviceregistry.data.Codecs.deviceTEncoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{PackageListItem, DeviceT}
import com.advancedtelematic.ota.deviceregistry.util.FakeSotaCoreClient
import com.advancedtelematic.ota.deviceregistry.{Resource, ResourcePropSpec}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Matchers
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.SpanSugar._
import slick.jdbc.MySQLProfile.api._

class MigrateBlacklistedPackagesSpec extends ResourcePropSpec
  with DatabaseSpec
  with ScalaFutures
  with Matchers
  with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.millis)

  private val genSotaCoreBlacklistedPackage: Gen[SotaCoreBlacklistedPackage] =
    for {
      packageId <- genPackageId
      comment   <- Gen.alphaNumStr
    } yield SotaCoreBlacklistedPackage(packageId, comment)

  private implicit val arbSotaCoreBlacklistedPackage = Arbitrary(genSotaCoreBlacklistedPackage)
  private implicit val arbNamespace = Arbitrary(Gen.const(Namespace.generate))

  private def createDevice(ns: Namespace, deviceT: DeviceT): Unit =
    Post(Resource.uri(api), deviceT).withHeaders(RawHeader("x-ats-namespace", ns.get)) ~> route ~> check {
      status shouldBe StatusCodes.Created
      ()
    }

  property("should store blacklisted packages from sota-core") {
    forAll(SizeRange(5)) { blacklist: Map[Namespace, (DeviceT, Seq[SotaCoreBlacklistedPackage])] =>
      val sotaCoreClient = new FakeSotaCoreClient
      val migrator = new MigrateBlacklistedPackages(sotaCoreClient)
      val expected = blacklist.flatMap { case (ns, (_, bps)) =>
        bps.map(bp => PackageListItem(ns, bp.packageId, bp.comment))
      }

      blacklist.foreach { case (ns, (d, _)) => createDevice(ns, d) }
      blacklist.foreach { case (ns, (_, bps)) => sotaCoreClient.addBlacklistedPackages(ns, bps) }

      migrator.run.futureValue

      eventually {
        val action = DBIO.sequence {
          blacklist.flatMap { case (ns, (_, bps)) =>
            bps.map(bp => PackageListItemRepository.fetchPackageListItem(ns, bp.packageId))
          }
        }
        val actual = db.run(action).futureValue
        actual should contain theSameElementsAs expected
      }
    }
  }

}
