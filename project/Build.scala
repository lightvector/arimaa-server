import sbt._
import sbt.Keys._
import com.earldouglas.xwp.XwpPlugin._

object ArimaaServerBuild extends Build {
  lazy val arimaaServer = Project(
    id = "arimaa-server",
    base = file("."),

    settings = Seq(
      scalaVersion := "2.11.6",

      libraryDependencies ++= Seq(
        "org.scalatra"  %% "scalatra"          % "2.4.0.RC1",
        "org.scalatra"  %% "scalatra-scalate"  % "2.4.0.RC1",
        "javax.servlet" %  "javax.servlet-api" % "3.1.0",
        "org.scalatest" %% "scalatest"         % "2.2.5" % "test",
        "org.scalatra"  %% "scalatra-json"     % "2.4.0.RC1",
        "org.json4s"    %% "json4s-jackson"    % "3.3.0.RC1",
        "com.typesafe.akka" %% "akka-actor" % "2.3.4",
        "net.databinder.dispatch" %% "dispatch-core" % "0.11.1"
      ),

      //Skip hash checking to work around hash conflict when downloading a library in sbt.
      //See https://github.com/lightvector/arimaa-server/issues/34
      checksums in update := Nil

    ) ++ jetty()

  )
}
