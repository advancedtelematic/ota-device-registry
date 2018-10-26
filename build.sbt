// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `ota-device-registry` =
  project
    .in(file("."))
    .enablePlugins(GitVersioning, BuildInfoPlugin, DockerPlugin, JavaAppPackaging)
    .settings(settings)
    .settings(
      resolvers += "Sonatype Nexus Repository Manager" at "http://nexus.advancedtelematic.com:8081/content/repositories/releases",
      resolvers += "Central" at "http://nexus.advancedtelematic.com:8081/content/repositories/central"
    )
    .settings(
      libraryDependencies ++= Seq(
        library.mariaDb,
        library.scalaCheck % Test,
        library.scalaTest  % Test,
        library.akkaHttpTestKit % Test,
        library.circeTesting % Test
      ),
      libraryDependencies += "org.tpolecat" %% "atto-core" % "0.6.2"
    )
    .settings(libraryDependencies ++= library.libAts)
    .settings(dependencyOverrides += "com.typesafe.akka" %% "akka-stream-kafka" % "0.18")

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      val scalaCheck = "1.13.5"
      val scalaTest  = "3.0.4"
      val libAts     = "0.1.2-47-ge214a0e"
      val akkaHttp = "10.0.10"
      val mariaDb = "1.4.4"
      val circe = "0.9.1"
    }
    val scalaCheck = "org.scalacheck" %% "scalacheck" % Version.scalaCheck
    val scalaTest  = "org.scalatest"  %% "scalatest"  % Version.scalaTest
    val libAts = Seq(
      "libats-messaging",
      "libats-messaging-datatype",
      "libats-slick",
      "libats-auth",
      "libats-http",
      "libats-metrics",
      "libats-metrics-akka",
      "libats-metrics-prometheus"
    ).map("com.advancedtelematic" %% _ % Version.libAts)
    val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % Version.akkaHttp
    val mariaDb = "org.mariadb.jdbc" % "mariadb-java-client" % Version.mariaDb
    val circeTesting = "io.circe" %% "circe-testing" % Version.circe
  }

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings =
commonSettings ++
gitSettings ++
scalafmtSettings ++
buildInfoSettings ++
dockerSettings ++
flywaySettings

lazy val commonSettings =
  Seq(
    scalaVersion := "2.12.4",
    organization := "com.advancedtelematic",
    organizationName := "ATS Advanced Telematic Systems GmbH",
    name := "device-registry",
    startYear := Some(2017),
    licenses += ("MPL-2.0", url("http://mozilla.org/MPL/2.0/")),
    scalacOptions ++= Seq(
      "-Ypartial-unification",
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

import com.typesafe.sbt.packager.docker.Cmd
lazy val dockerSettings = Seq(
  dockerRepository := Some("advancedtelematic"),
  packageName := packageName.value,
  dockerUpdateLatest := true,
  dockerBaseImage := "openjdk:8u151-jre-alpine",
  dockerCommands ++= Seq(
    Cmd("USER", "root"),
    Cmd("RUN", "apk upgrade --update && apk add --update bash coreutils"),
    Cmd("USER", (daemonUser in Docker).value)
  )
)

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := false,
    scalafmtOnCompile.in(Sbt) := false,
    scalafmtVersion := "1.3.0"
  )

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
  buildInfoPackage := organization.value,
  buildInfoOptions ++= Seq(BuildInfoOption.ToJson, BuildInfoOption.ToMap)
)

lazy val flywaySettings = Seq(
  flywayUrl := sys.env.get("DEVICE_REGISTRY_DB_URL").orElse(sys.props.get("device-registry.db.url")).getOrElse("jdbc:mysql://localhost:3306/device_registry"),
  flywayUser := sys.env.get("DEVICE_REGISTRY_DB_USER").orElse(sys.props.get("device-registry.db.user")).getOrElse("device_registry"),
  flywayPassword := sys.env.get("DEVICE_REGISTRY_DB_PASSWORD").orElse(sys.props.get("device-registry.db.password")).getOrElse("device_registry")
)
