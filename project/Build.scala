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
        "org.json4s"    %% "json4s-jackson"    % "3.3.0.RC1"
      ),

      //TODO(lightvector): Is this concerning? This is a dependency of scalatra-json, and I guess whoever owns this library
      //messed up when uploading things and updating the published hash? (either that or I got a bad/hacked version of this library
    )
      //Working around error in sbt:
      //[warn] problem while downloading module descriptor:
      //https://repo1.maven.org/maven2/com/thoughtworks/paranamer/paranamer/2.7/paranamer-2.7.pom:
      //invalid sha1: expected=deea673ac6c495cfca3c2fecce26bd9b67295e5b computed=36ef984e4403f800d8aeb5b82045ea4cec0db07b
      checksums in update := Nil
    ) ++ jetty()

  )
}
