ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.18"

lazy val akkaVersion      = "2.8.5"
lazy val akkaHttpVersion  = "10.5.3"

lazy val root = (project in file("."))
  .settings(
    name := "akka-actor-model-demo",
    organization := "com.codemash",
    libraryDependencies ++= Seq(
      // Akka Typed core
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,

      // Cluster & Sharding
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,

      // Persistence (for Option B demo)
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,

      // HTTP
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",

      // Test
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    // Helpful for running multiple cluster nodes from sbt
    fork := true,
    javaOptions ++= Seq(
      "-Dconfig.file=src/main/resources/application.conf",
      "-Dlogback.configurationFile=src/main/resources/logback.xml"
    ),
    // Forward -Dakka.* and -Dapp.* properties from the sbt JVM to the forked run JVM
    run / javaOptions ++= sys.props.collect {
      case (k, v) if k.startsWith("akka.") || k.startsWith("app.") => s"-D$k=$v"
    }.toSeq
  )
