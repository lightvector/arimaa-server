package org.playarimaa.server

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import slick.driver.{H2Driver, JdbcProfile, PostgresDriver}
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server.chat._
import org.playarimaa.server.game._

sealed trait WhichDB
case object PostgresDB extends WhichDB
case object H2DB extends WhichDB

object DatabaseConfig {

  val logger =  LoggerFactory.getLogger(getClass)

  //Switch which db we load based on whether we are in prod or test. See /project/Build.scala for the run configurations
  //that take advantage of this
  lazy val whichDB: WhichDB = {
    System.getProperty("isProd") match {
      case "true" =>
        logger.info("Using PostgresDB")
        PostgresDB
      case "false" =>
        logger.info("Using H2DB")
        H2DB
      case null => throw new Exception("isProd system property undefined, expected \"true\" or \"false\"")
      case x => throw new Exception("Unexpected value for isProd system property, expected \"true\" or \"false\": " + x)
    }
  }

  lazy val driver: JdbcProfile = {
    whichDB match {
      case PostgresDB => PostgresDriver
      case H2DB => H2Driver
    }
  }

  import driver.api._

  def getDB() : Database = {
    whichDB match {
      case H2DB =>
        val db = Database.forConfig("h2DBConfig")
        createTablesIfNotCreated(db)
        db
      case PostgresDB =>
        Database.forConfig("postgresDBConfig")
    }
  }

  var tablesCreated = false
  def createTablesIfNotCreated(db: Database): Unit = {
    if(!tablesCreated) {
      tablesCreated = true
      Await.result(db.run(DBIO.seq(
        ( ChatSystem.table.schema ++
          Games.gameTable.schema ++
          Games.movesTable.schema ++
          Accounts.table.schema
        ).create
      )), Duration.Inf)
    }
  }
}

