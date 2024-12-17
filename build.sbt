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
libraryDependencies += "im.paideia" %% "paideia-sdk" % "1.0.0-rc3+12-32f4e675-SNAPSHOT"

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

resolvers ++= Seq(
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Sonatype Snapshots S1" at "https://s01.oss.sonatype.org/content/repositories/snapshots/",
  "Bintray" at "https://jcenter.bintray.com/"
)

Universal / javaOptions ++= Seq(
  "-Dpidfile.path=/dev/null"
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "im.paideia.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "im.paideia.binders._"
