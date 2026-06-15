import sbtassembly.AssemblyPlugin.autoImport.*

ThisBuild / scalaVersion := "3.3.7"

ThisBuild / version := "0.1.0-SNAPSHOT"

val CirceVersion = "0.14.6"
val Http4sVersion = "0.23.23"
val cirisVersion = "3.11.0"

lazy val root = (project in file("."))
  .settings(
    name         := "transaction-simulator",

    libraryDependencies ++= Seq(
      // Effects
      "org.typelevel"  %% "cats-effect"         % "3.5.4",

      // Streaming
      "co.fs2"         %% "fs2-io"              % "3.10.2",

      // HTTP Client — Ember, consistent with Typelevel stack
      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion,
      "org.http4s" %% "http4s-circe"        % Http4sVersion,
      "org.http4s" %% "http4s-dsl"          % Http4sVersion,
      // JSON
      "io.circe"       %% "circe-generic"       % CirceVersion,
      "io.circe"       %% "circe-literal"       % CirceVersion,

      // Config
      "is.cir" %% "ciris" % cirisVersion,

      // Logging
      "org.typelevel"  %% "log4cats-slf4j"      % "2.7.0",
      "ch.qos.logback"  % "logback-classic"     % "1.5.6"
    ),

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "reference.conf"                     => MergeStrategy.concat
      case _                                    => MergeStrategy.first
    },

    assembly / assemblyJarName := "transaction-simulator.jar"
  )