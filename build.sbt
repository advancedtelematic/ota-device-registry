// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `ota-device-registry` =
  project
    .in(file("."))
    .enablePlugins(AutomateHeaderPlugin, GitVersioning, BuildInfoPlugin, DockerPlugin, JavaAppPackaging)
    .settings(settings)
    .settings(
      resolvers += "Sonatype Nexus Repository Manager" at "http://nexus.advancedtelematic.com:8081/content/repositories/releases"
    )
    .settings(
      libraryDependencies ++= Seq(
        library.mariaDb,
        library.scalaCheck % Test,
        library.scalaTest  % Test,
        library.akkaHttpTestKit % Test
      )
    )
    .settings(libraryDependencies ++= library.libAts)

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      val scalaCheck = "1.13.5"
      val scalaTest  = "3.0.4"
      val libAts     = "0.1.1-1-gf9dc44c"
      val akkaHttp = "10.0.10"
      val mariaDb = "1.4.4"
    }
    val scalaCheck = "org.scalacheck" %% "scalacheck" % Version.scalaCheck
    val scalaTest  = "org.scalatest"  %% "scalatest"  % Version.scalaTest
    val libAts = Seq(
      "libats-messaging",
      "libats-messaging-datatype",
      "libats-slick",
      "libats-auth",
      "libats-http"
    ).map("com.advancedtelematic" %% _ % Version.libAts)
    val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % Version.akkaHttp
    val mariaDb = "org.mariadb.jdbc" % "mariadb-java-client" % Version.mariaDb
  }

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings =
commonSettings ++
gitSettings ++
scalafmtSettings ++
buildInfoSettings

lazy val commonSettings =
  Seq(
    scalaVersion := "2.12.4",
    organization := "com.advancedtelematic",
    organizationName := "ATS Advanced Telematic Systems GmbH",
    startYear := Some(2017),
    licenses += ("MPL-2.0", url("http://mozilla.org/MPL/2.0/")),
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8"
    ),
    unmanagedSourceDirectories.in(Compile) := Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value)
  )

lazy val gitSettings = Seq(
    git.useGitDescribe := true,
  )

lazy val dockerSettings = Seq(
  dockerRepository in Docker := Some("advancedtelematic"),
  packageName in Docker := packageName.value,
  dockerUpdateLatest in Docker := true,
  dockerBaseImage in Docker := "advancedtelematic/jre:8u151"
)

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := true,
    scalafmtOnCompile.in(Sbt) := false,
    scalafmtVersion := "1.3.0"
  )

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
  buildInfoPackage := organization.value,
  buildInfoOptions ++= Seq(BuildInfoOption.ToJson, BuildInfoOption.ToMap)
)
