import sbt._
import sbt.Keys._
import com.earldouglas.xwp.XwpPlugin._

object ArimaaServerBuild extends Build {
  lazy val arimaaServer = Project(
    id = "arimaa-server",
    base = file("."),

    settings = Seq(
      scalaVersion := "2.11.6",

      scalacOptions += "-deprecation",
      scalacOptions += "-feature",
      scalacOptions += "-Xlint",

      libraryDependencies ++= Seq(
        "com.typesafe" % "config" % "1.2.1",
        "com.typesafe.akka" %% "akka-actor" % "2.3.4",
        "com.typesafe.akka" %% "akka-testkit" % "2.3.4",
        "com.h2database" % "h2" % "1.3.170",
        "com.typesafe.slick" %% "slick" % "3.0.0",
        "javax.mail" % "javax.mail-api" % "1.5.4",
        "javax.servlet" %  "javax.servlet-api" % "3.1.0",
        "joda-time" % "joda-time" % "2.8.2",
        "net.databinder.dispatch" %% "dispatch-core" % "0.11.1",
        "org.eclipse.jetty"           %  "jetty-plus"          % "9.2.10.v20150310"     % "container;provided",
        "org.eclipse.jetty"           %  "jetty-webapp"        % "9.2.10.v20150310"     % "container",
        "org.eclipse.jetty.websocket" %  "websocket-server"    % "9.2.10.v20150310"     % "container;provided",
        "org.scalatest" %% "scalatest"         % "2.2.5" % "test",
        "org.scalatra"  %% "scalatra"          % "2.4.0-RC2-2",
        "org.scalatra"  %% "scalatra-scalate"  % "2.4.0-RC2-2",
        "org.scalatra"  %% "scalatra-scalatest" % "2.4.0-RC2-2" % "test",
        "org.scalatra"  %% "scalatra-json"     % "2.4.0-RC2-2",
        "org.scalatra"  %% "scalatra-specs2"     % "2.4.0-RC2-2"  % "test",
        "org.scalatra"  %% "scalatra-atmosphere" % "2.4.0-RC2-2",
        "org.json4s"    %% "json4s-jackson"    % "3.3.0.RC1",
        "org.mindrot" % "jbcrypt" % "0.3m",
        "org.slf4j" % "slf4j-api" % "1.7.5",
        "org.slf4j" % "slf4j-simple" % "1.7.5"
      ),

      resolvers += "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases",

      //Full backtraces
      testOptions in Test += Tests.Argument("-oF")

    ) ++ jetty()
  )
}
