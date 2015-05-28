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
        "org.scalatra" %% "scalatra" % "2.4.0.RC1",
        "org.scalatra" %% "scalatra-scalate" % "2.4.0.RC1",
        "javax.servlet" % "javax.servlet-api" % "3.1.0",
        "org.scalatest" %% "scalatest" % "2.2.5" % "test"
      )
    ) ++ jetty()
  )
}