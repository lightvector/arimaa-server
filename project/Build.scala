import sbt._
import sbt.Keys._
import com.earldouglas.xwp.XwpPlugin._

object ArimaaServerBuild extends Build {
  lazy val arimaaServer = Project(
    id = "arimaa-server",
    base = file("."),

    settings = Seq(
      scalaVersion := "2.11.6",

      scalacOptions ++= Seq(
          "-deprecation",
          "-feature",
          "-language:existentials",
          "-unchecked",
          "-Xfatal-warnings",
          "-Xlint",
          "-Yno-adapted-args",
          "-Ywarn-dead-code",
          "-Ywarn-numeric-widen",
          "-Ywarn-value-discard",
          "-Xfuture"
      ),

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
        "org.scalanlp"  %% "breeze" % "0.11.2",
        "org.scalatest" %% "scalatest"         % "2.2.5" % "test",
        "org.scalatra"  %% "scalatra"          % "2.4.0-RC2-2",
        "org.scalatra"  %% "scalatra-scalate"  % "2.4.0-RC2-2",
        "org.scalatra"  %% "scalatra-scalatest" % "2.4.0-RC2-2" % "test",
        "org.scalatra"  %% "scalatra-json"     % "2.4.0-RC2-2",
        "org.scalatra"  %% "scalatra-specs2"     % "2.4.0-RC2-2"  % "test",
        "org.scalatra"  %% "scalatra-atmosphere" % "2.4.0-RC2-2",
        "org.scala-lang" % "scala-compiler" % "2.11.6",
        "org.scala-lang" % "scala-library" % "2.11.6",
        "org.scala-lang" % "scala-reflect" % "2.11.6",
        "org.json4s"    %% "json4s-jackson"    % "3.3.0.RC1",
        "org.mindrot" % "jbcrypt" % "0.3m",
        "org.slf4j" % "slf4j-api" % "1.7.5",
        "org.slf4j" % "slf4j-simple" % "1.7.5",
        "org.postgresql" % "postgresql" % "9.3-1100-jdbc4",
        "com.zaxxer" % "HikariCP-java6" % "2.3.3"
      ),

      resolvers += "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases",

      //Full backtraces
      testOptions in Test += Tests.Argument("-oF"),

      //Different settings in test
      fork in Test := true,
      javaOptions in Test := Seq("-DisProd=false"),

      //New commands in sbt
      commands ++= Seq(startServerProd, startServerTest)

    ) ++ jetty()
  )

  //Start the server in production mode (currently, this affects how we initialize the DB - see DatabaseConfig.scala)
  def startServerProd = Command.command("startServerProd") { state =>
    val state2 = Project.extract(state).append(Seq(javaOptions in container += "-DisProd=true"), state)
    Project.extract(state2).runTask(start in container, state2)
    state
  }

  //Start the server in test mode (currently, this affects how we initialize the DB - see DatabaseConfig.scala)
  def startServerTest = Command.command("startServerTest") { state =>
    val state2 = Project.extract(state).append(Seq(javaOptions in container += "-DisProd=false"), state)
    Project.extract(state2).runTask(start in container, state2)
    state
  }

}
