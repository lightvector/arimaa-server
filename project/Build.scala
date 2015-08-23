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

      libraryDependencies ++= Seq(
        "org.scalatra"  %% "scalatra"          % "2.4.0.RC1",
        "org.scalatra"  %% "scalatra-scalate"  % "2.4.0.RC1",
        "org.scalatra"  %% "scalatra-scalatest" % "2.4.0.RC1" % "test",
        "javax.servlet" %  "javax.servlet-api" % "3.1.0",
        "org.scalatest" %% "scalatest"         % "2.2.5" % "test",
        "org.scalatra"  %% "scalatra-json"     % "2.4.0.RC1",
        "org.json4s"    %% "json4s-jackson"    % "3.3.0.RC1",
        "com.typesafe.akka" %% "akka-actor" % "2.3.4",
        "com.typesafe.akka" %% "akka-testkit" % "2.3.4",
        "net.databinder.dispatch" %% "dispatch-core" % "0.11.1",
        "com.typesafe.slick" %% "slick" % "3.0.0",
        "org.slf4j" % "slf4j-api" % "1.7.5",
        "org.slf4j" % "slf4j-simple" % "1.7.5",
        "com.h2database" % "h2" % "1.3.170",
        "org.mindrot" % "jbcrypt" % "0.3m"
      ),

      //Full backtraces
      testOptions in Test += Tests.Argument("-oF")

    ) ++ jetty()
  )
}
