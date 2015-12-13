package org.playarimaa.server

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.playarimaa.server.chat._
import org.playarimaa.server.game._

object DatabaseConfig {
  val driver: slick.driver.JdbcProfile = slick.driver.H2Driver
  //val driver: slick.driver.JdbcProfile = slick.driver.PostgresDriver

  import driver.api._

  def createDB(configName: String) : Database = {
    //Initialize in-memory database and create tables for testing
    val db = Database.forConfig(configName)
    Await.result(db.run(DBIO.seq(
      ( ChatSystem.table.schema ++
        Games.gameTable.schema ++
        Games.movesTable.schema ++
        Accounts.table.schema
      ).create
    )), Duration.Inf)
    db
  }
}
