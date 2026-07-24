name := "life-dashboard-api"
organization := "com.lifedashboard"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.3"

libraryDependencies ++= Seq(
  guice,
  ws,
  jdbc,
  evolutions,
  ehcache,
  "org.playframework" %% "play-slick" % "6.1.0",
  "org.postgresql" % "postgresql" % "42.7.3",
  "org.playframework.anorm" %% "anorm" % "2.8.1",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
)

// Allow CORS from Next.js frontend
PlayKeys.devSettings := Seq("play.server.http.port" -> "9000")