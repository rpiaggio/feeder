name := "feeder"

version := "0.1"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.1.8",
  "com.typesafe.akka" %% "akka-stream" % "2.5.22",
  "com.beachape" %% "enumeratum" % "1.5.13",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.1",
  "org.jsoup" % "jsoup" % "1.11.3"
)