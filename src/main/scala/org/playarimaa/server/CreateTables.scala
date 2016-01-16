package org.playarimaa.server

import scala.concurrent.{ ExecutionContext, ExecutionContext$, Future, Promise, Await }
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global
import org.playarimaa.server.DatabaseConfig.driver.api._

object CreateTables {

  def main(args: Array[String]): Unit = {
    println("Initializing db")
    val db = DatabaseConfig.getDB()
    println("Creating tables")
    DatabaseConfig.createTablesIfNotCreated(db)
    println("Tables created")
  }
}
