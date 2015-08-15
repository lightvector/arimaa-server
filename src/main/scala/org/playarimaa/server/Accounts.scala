package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}
import slick.driver.H2Driver.api._
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp

object Accounts {
  val table = TableQuery[AccountTable]
}

case class Account(
  lowercaseName: Username,
  username: Username,
  email: Email,
  passwordHash: String,
  isBot: Boolean,
  createdTime: Timestamp
)

class Accounts(val db: Database)(implicit ec: ExecutionContext) {

  def getByName(username: Username): Future[Option[Account]] = {
    val lowercaseName = username.toLowerCase
    val query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    db.run(query.result).map(_.headOption)
  }

  def getByNameOrEmail(usernameOrEmail: String): Future[Option[Account]] = {
    val lowercaseName = usernameOrEmail.toLowerCase
    val query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    db.run(query.result).flatMap { result =>
      result.headOption match {
        case Some(account) => Future(Some(account))
        case None =>
          val query = Accounts.table.filter(_.email === usernameOrEmail)
          db.run(query.result).map(_.headOption)
      }
    }
  }

  def add(account: Account): Future[Unit] = {
    val query = Accounts.table += account
    db.run(DBIO.seq(query))
  }
}

class AccountTable(tag: Tag) extends Table[Account](tag, "accountTable") {
  def lowercaseName = column[String]("lowercaseName", O.PrimaryKey)
  def username = column[String]("username")
  def email = column[String]("email")
  def passwordHash = column[String]("passwordHash")
  def isBot = column[Boolean]("isBot")
  def createdTime = column[Double]("createdTime")

  def * = (lowercaseName, username, email, passwordHash, isBot, createdTime) <> (Account.tupled, Account.unapply)
}
