package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt, DurationDouble}
import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import slick.driver.H2Driver.api._
import slick.lifted.{PrimaryKey,ProvenShape}
import org.slf4j.{Logger, LoggerFactory}
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import akka.actor.{Scheduler,Cancellable}
import akka.pattern.{after}

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
  isGuest: Boolean,

  lastLogin: Timestamp,
  gameStats: AccountGameStats
)
{
  def info : SimpleUserInfo = {
    SimpleUserInfo(
      name = username,
      rating = gameStats.rating,
      isBot = isBot,
      isGuest = isGuest
    )
  }
}

case class AccountGameStats(
  numGamesGold: Int,
  numGamesSilv: Int,
  numGamesWon: Int,
  numGamesLost: Int,
  rating: Double,
  ratingStdev: Double
)

case object AccountGameStats {
  val initialRating: Double = 1500
  val initial: AccountGameStats = new AccountGameStats(0,0,0,0,initialRating,500)
}

class Accounts(val db: Database, val scheduler: Scheduler)(implicit ec: ExecutionContext) {

  val logger =  LoggerFactory.getLogger(getClass)

  //Returns any account with this name
  def getByName(username: Username, excludeGuests: Boolean): Future[Option[Account]] = {
    val lowercaseName = username.toLowerCase
    var query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    query = if(excludeGuests) query.filter(_.isGuest === false) else query
    db.run(query.result).map(_.headOption)
  }

  //Returns all accounts with this name or email
  def getByNameOrEmail(usernameOrEmail: String, excludeGuests: Boolean): Future[List[Account]] = {
    val lowercaseName = usernameOrEmail.toLowerCase
    var query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    query = if(excludeGuests) query.filter(_.isGuest) else query
    db.run(query.result).flatMap { result =>
      result.headOption match {
        case Some(account) => Future.successful(List(account))
        case None =>
          val query = Accounts.table.filter(_.email === usernameOrEmail)
          db.run(query.result).map(_.toList)
      }
    }
  }

  //Add a new account to the table.
  //In the case where there is an existing guest account, replaces the guest account with the new account.
  def add(account: Account): Future[Unit] = {
    getByName(account.username, excludeGuests=true).map {
      case Some(acct) =>
        if(acct.isGuest) {
          val lowercaseName = account.username.toLowerCase
          val query = Accounts.table.filter(_.lowercaseName === lowercaseName).update(account)
          db.run(DBIO.seq(query))
        }
        else
          Future.failed(new Exception("Account for " + account.username + " already exists"))
      case None =>
        val query = Accounts.table += account
        db.run(DBIO.seq(query))
    }
  }

  //Remove an account from the table if it's a guest account
  def removeIfGuest(username: Username): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).filter(_.isGuest).delete
    db.run(DBIO.seq(query))
  }

  def setPasswordHash(username: Username, passwordHash: String): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.passwordHash).update(passwordHash)
    db.run(query).map {
      case 1 => ()
      case 0 => throw new Exception("Failed to set new password, account was not found")
      case x => throw new Exception("Failed to set new password, found " + x + " accounts")
    }
  }

  def setEmail(username: Username, email: Email): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.email).update(email)
    db.run(query).map {
      case 1 => ()
      case 0 => throw new Exception("Failed to set new email, account was not found")
      case x => throw new Exception("Failed to set new email, found " + x + " accounts")
    }
  }

  def refreshLastLogin(username: Username): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val now = Timestamp.get
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.lastLogin).update(now)
    db.run(DBIO.seq(query))
  }

  def setGameStats(username: Username, stats: AccountGameStats): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val now = Timestamp.get
    val query: DBIO[Int] =
      Accounts.table.filter(_.lowercaseName === lowercaseName).map{
        a => (a.numGamesGold, a.numGamesSilv, a.numGamesWon, a.numGamesLost, a.rating, a.ratingStdev)
      }.update(AccountGameStats.unapply(stats).get)
    db.run(DBIO.seq(query))
  }

  private def doUpdateGameStats(username: Username, f:(AccountGameStats => AccountGameStats)): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val now = Timestamp.get
    //Build up a transaction
    val query: DBIO[Int] =
      //Get the account's existing stats...
      Accounts.table.filter(_.lowercaseName === lowercaseName).map{
        a => (a.numGamesGold, a.numGamesSilv, a.numGamesWon, a.numGamesLost, a.rating, a.ratingStdev)
      }.result.flatMap { result =>
        //Once we have a result...
        result.headOption match {
          case None => DBIO.failed(new Exception("User not found when updating game stats"))
          case Some(result) =>
            //Parse out the existing stats and map them with the user's provided f
            val stats = f((AccountGameStats.apply _).tupled.apply(result))
            //Write the result back
            Accounts.table.filter(_.lowercaseName === lowercaseName).map{
              a => (a.numGamesGold, a.numGamesSilv, a.numGamesWon, a.numGamesLost, a.rating, a.ratingStdev)
            }.update(AccountGameStats.unapply(stats).get)
        }
      }.transactionally //And do this all within a db transaction
    db.run(DBIO.seq(query))
  }

  //Tries updating the game stats a few times, then fails
  def updateGameStats(username: Username, f:(AccountGameStats => AccountGameStats)): Future[Unit] = {
    doUpdateGameStats(username,f).recoverWith { case _ => after(1 seconds,scheduler) {
      doUpdateGameStats(username,f).recoverWith { case _ => after(3 seconds,scheduler) {
        doUpdateGameStats(username,f).recoverWith { case _ => after(10 seconds,scheduler) {
          doUpdateGameStats(username,f)
      }}}}}}
  }



  // def gameStats : ProvenShape[AccountGameStats] = (numGamesGold, numGamesSilv, numGamesWon, numGamesLost, rating, ratingStdev).shaped <> (
  //   //Database shape -> Scala object
  //   { case (numGamesGold, numGamesSilv, numGamesWon, numGamesLost, rating, ratingStdev) =>
  //     AccountGameStats(numGamesGold, numGamesSilv, numGamesWon, numGamesLost, rating, ratingStdev)
  //   },
  //   //Scala object -> Database shape
  //   { a: AccountGameStats =>
  //     Some((a.numGamesGold, a.numGamesSilv, a.numGamesWon, a.numGamesLost, a.rating, a.ratingStdev))
  //   }
  // )


}

class AccountTable(tag: Tag) extends Table[Account](tag, "accountTable") {
  def lowercaseName : Rep[String] = column[String]("lowercaseName")
  def username : Rep[String] = column[String]("username")
  def email : Rep[String] = column[String]("email")
  def passwordHash : Rep[String] = column[String]("passwordHash")
  def isBot : Rep[Boolean] = column[Boolean]("isBot")
  def createdTime : Rep[Double] = column[Double]("createdTime")
  def isGuest : Rep[Boolean] = column[Boolean]("isGuest")
  def lastLogin : Rep[Double] = column[Double]("lastLogin")
  def numGamesGold : Rep[Int] = column[Int]("numGamesGold")
  def numGamesSilv : Rep[Int] = column[Int]("numGamesSilv")
  def numGamesWon : Rep[Int] = column[Int]("numGamesWon")
  def numGamesLost : Rep[Int] = column[Int]("numGamesLost")
  def rating : Rep[Double] = column[Double]("rating")
  def ratingStdev : Rep[Double] = column[Double]("ratingStdev")

  def * : ProvenShape[Account] =
    (lowercaseName, username, email, passwordHash, isBot, createdTime, isGuest, lastLogin,
      (numGamesGold, numGamesSilv, numGamesWon, numGamesLost, rating, ratingStdev)).shaped <> (
    //Database shape -> Scala object
    { case (lowercaseName, username, email, passwordHash, isBot, createdTime, isGuest, lastLogin, stats) =>
      Account(lowercaseName, username, email, passwordHash, isBot, createdTime, isGuest, lastLogin,
        (AccountGameStats.apply _).tupled.apply(stats)
      )
    },
    //Scala object -> Database shape
    { a: Account =>
      Some((
        a.lowercaseName,a.username,a.email,a.passwordHash,a.isBot,a.createdTime,a.isGuest,a.lastLogin,
        AccountGameStats.unapply(a.gameStats).get
      ))
    }
  )
}
