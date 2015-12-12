package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}
import slick.driver.H2Driver.api._
import slick.lifted.{PrimaryKey,ProvenShape}
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
  createdTime: Timestamp,
  isGuest: Boolean
)

class Accounts(val db: Database)(implicit ec: ExecutionContext) {

  //Guests held separately here and not entered into the database
  var guests: Map[Username,Account] = Map()

  //Returns any account with this name, potentially including guests
  def getByName(username: Username): Future[Option[Account]] = {
    val lowercaseName = username.toLowerCase
    val query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    db.run(query.result).map { result =>
      result.headOption match {
        case Some(account) => Some(account)
        case None => this.synchronized { guests.get(lowercaseName) }
      }
    }
  }

  def getNonGuestByName(username: Username): Future[Option[Account]] = {
    val lowercaseName = username.toLowerCase
    val query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    db.run(query.result).map(_.headOption)
  }

  //Returns all non-guest accounts with this name or email
  def getNonGuestByNameOrEmail(usernameOrEmail: String): Future[List[Account]] = {
    val lowercaseName = usernameOrEmail.toLowerCase
    val query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    db.run(query.result).flatMap { result =>
      result.headOption match {
        case Some(account) => Future.successful(List(account))
        case None =>
          val query = Accounts.table.filter(_.email === usernameOrEmail)
          db.run(query.result).map(_.toList)
      }
    }
  }

  def add(account: Account): Future[Unit] = {
    if(account.isGuest)
      getByName(account.lowercaseName).flatMap { result =>
        result match {
          case Some(_) => throw new Exception("Cannot add guest account for account already in db")
          case None =>
            this.synchronized { guests += (account.lowercaseName -> account) }

            //Handle race condition by checking again
            getByName(account.lowercaseName).map { result => this.synchronized {
              result match {
                case Some(acct) =>
                  if(!acct.isGuest) {
                    guests -= account.lowercaseName
                    throw new Exception("Cannot add guest account for account already in db")
                  }
                case None => ()
              }
            }}
        }
      }
    else {
      val query = Accounts.table += account
      db.run(DBIO.seq(query))
    }
  }

  def isGuest(username: Username): Boolean = {
    val lowercaseName = username.toLowerCase
    this.synchronized {
      guests.contains(username)
    }
  }

  def removeGuest(username: Username): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    this.synchronized {
      guests -= username
      Future.successful(())
    }
  }

  def setPasswordHash(username: Username, passwordHash: String): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.passwordHash).update(passwordHash)
    db.run(DBIO.seq(query))
  }

  def setEmail(username: Username, email: Email): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.email).update(email)
    db.run(DBIO.seq(query))
  }

}

class AccountTable(tag: Tag) extends Table[Account](tag, "accountTable") {
  def lowercaseName : Rep[String] = column[String]("lowercaseName", O.PrimaryKey)
  def username : Rep[String] = column[String]("username")
  def email : Rep[String] = column[String]("email")
  def passwordHash : Rep[String] = column[String]("passwordHash")
  def isBot : Rep[Boolean] = column[Boolean]("isBot")
  def createdTime : Rep[Double] = column[Double]("createdTime")

  def * : ProvenShape[Account] = (lowercaseName, username, email, passwordHash, isBot, createdTime).shaped <> (
    //Database shape -> Scala object
    { case (lowercaseName, username, email, passwordHash, isBot, createdTime) =>
      Account(lowercaseName, username, email, passwordHash, isBot, createdTime, isGuest=false)
    },
    //Scala object -> Database shape
    { a: Account =>
      if(a.isGuest)
        throw new Exception("BUG: Tried to insert guest into accounts table")
      Some((a.lowercaseName,a.username,a.email,a.passwordHash,a.isBot,a.createdTime))
    }
  )
}
