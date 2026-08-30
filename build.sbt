import com.typesafe.sbt.packager.docker.ExecCmd
name := """paideia-state-main"""
organization := "im.paideia"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.17"

import com.typesafe.sbt.packager.docker.DockerChmodType
dockerChmodType := DockerChmodType.UserGroupWriteExecute
import com.typesafe.sbt.packager.docker.DockerPermissionStrategy
dockerPermissionStrategy := DockerPermissionStrategy.MultiStage
dockerUpdateLatest := true
dockerBaseImage := "openjdk:11"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies += "im.paideia" %% "paideia-sdk" % "1.0.0-rc4-SNAPSHOT"

// org.ethereum:leveldbjni-all (transitive via plasma-toolkit) is not on any public repo;
// com.halibobor bundles LevelDB 1.23, which reads the .ldb files written by the org.ethereum build (1.18)
// that production ran; io.github.tronprotocol bundles an older LevelDB that only knows .sst files.
excludeDependencies += ExclusionRule("org.ethereum", "leveldbjni-all")
libraryDependencies += "com.halibobor" % "leveldbjni-all" % "1.23.2"

// ergo-wallet 6.0.0 declares circe 0.13 while sigma-state 6.0.6 declares 0.14; upstream appkit ships that mix.
ThisBuild / evictionErrorLevel := Level.Warn

dependencyOverrides ++= Seq(
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
  "org.slf4j" % "slf4j-api" % "1.7.36"
)

// excludeDependencies ++= Seq(
//   ExclusionRule("org.slf4j")
// )

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.19",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.zeromq" % "jeromq" % "0.5.3"
)

Universal / javaOptions ++= Seq(
  "-Dpidfile.path=/dev/null"
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "im.paideia.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "im.paideia.binders._"
