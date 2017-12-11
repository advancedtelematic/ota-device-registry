addSbtPlugin("com.lucidchart"    % "sbt-scalafmt"  % "1.14")
addSbtPlugin("com.typesafe.sbt"  % "sbt-git"       % "0.9.3")
addSbtPlugin("de.heikoseeberger" % "sbt-header"    % "4.0.0")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.2")
addSbtPlugin("net.vonbuchholtz" % "sbt-dependency-check" % "0.1.10")
libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25" // Needed by sbt-git
