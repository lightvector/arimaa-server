package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt, DurationDouble}
import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import org.playarimaa.server.DatabaseConfig.driver.api._
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
  lowercaseName: UserID,
  //Distinct from lowercaseName so that we can remember the username captialization
  username: Username, 
  email: Email,
  //If Some, the user needs to verify that his/her email address is correct with this auth or else the account will be deleted after some time
  emailVerifyNeeded: Option[Auth],
  passwordHash: String,
  isBot: Boolean,
  createdTime: Timestamp,
  isGuest: Boolean,
  isAdmin: Boolean,

  lastLogin: Timestamp,
  gameStats: AccountGameStats,
  priorRating: Rating
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

//TODO add query to display user info and rating and stats
case class AccountGameStats(
  numGamesGold: Int,
  numGamesSilv: Int,
  numGamesWon: Int,
  numGamesLost: Int,
  rating: Rating
)

case object AccountGameStats {
  def initial(priorRating: Rating): AccountGameStats = new AccountGameStats(0,0,0,0,priorRating)

  //Each time we fail to update user stats in a transaction, wait this many seconds then try again, failing out right if we hit the end of the list.
  val updateRetryDelays: List[Double] = List(1.0,3.0,10.0)

  def ofDB(stats : (Int,Int,Int,Int,Double,Double)) : AccountGameStats = {
    stats match { case (numGamesGold, numGamesSilv, numGamesWon, numGamesLost, rating, ratingStdev) =>
      AccountGameStats(numGamesGold, numGamesSilv, numGamesWon, numGamesLost, Rating(rating,ratingStdev))
    }
  }

  def toDB(gs: AccountGameStats) : (Int,Int,Int,Int,Double,Double) = {
    (gs.numGamesGold, gs.numGamesSilv, gs.numGamesWon, gs.numGamesLost, gs.rating.mean, gs.rating.stdev)
  }
}

class Accounts(val domainName: String, val db: Database, val scheduler: Scheduler)(implicit ec: ExecutionContext) {

  val logger =  LoggerFactory.getLogger(getClass)

  //Returns any account with this name
  //Usernames are not case sensitive, although the case the user specified on registration is remembered
  def getByName(username: Username, excludeGuests: Boolean): Future[Option[Account]] = {
    val lowercaseName = username.toLowerCase
    var query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    query = if(excludeGuests) query.filter(_.isGuest === false) else query
    db.run(query.result).map(_.headOption)
  }

  //Returns all accounts with this name or email
  //Usernames are not case sensitive, although the case the user specified on registration is remembered
  def getByNameOrEmail(usernameOrEmail: String, excludeGuests: Boolean): Future[List[Account]] = {
    val lowercaseName = usernameOrEmail.toLowerCase
    var query = Accounts.table.filter(_.lowercaseName === lowercaseName)
    query = if(excludeGuests) query.filter(_.isGuest === false) else query
    db.run(query.result).flatMap { result =>
      result.headOption match {
        case Some(account) =>
          Future.successful(List(account))
        case None =>
          val query = Accounts.table.filter(_.email === usernameOrEmail)
          db.run(query.result).map(_.toList)
      }
    }
  }

  //Get all accounts whose emails are not verified
  def getAllUnverified(): Future[List[Account]] = {
    val query = Accounts.table.filter(_.emailVerifyNeeded.isDefined)
    db.run(query.result).map(_.toList)
  }

  //Get all accounts, optionally excluding guests
  def getAll(excludeGuests: Boolean): Future[List[Account]] = {
    var query: Query[AccountTable,Account,Seq] = Accounts.table
    query = if(excludeGuests) query.filter(_.isGuest === false) else query
    db.run(query.result).map(_.toList)
  }

  //Add a new account to the table.
  //In the case where there is an existing guest account, replaces the guest account with the new account.
  def add(account: Account): Future[Unit] = {
    getByName(account.username, excludeGuests=false).flatMap {
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
    logger.info("Removing guest account: " + username)
    db.run(DBIO.seq(query))
  }

  //Remove an account if its email is not verified
  def removeIfUnverified(username: Username): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).filter(_.emailVerifyNeeded.isDefined).delete
    logger.info("Removing unverified account: " + username)
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

  def setEmail(username: Username, email: Email, emailVerifyNeeded: Option[Auth]): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.email).update(email)
    db.run(query).flatMap {
      case 1 => setEmailVerifyNeeded(username, emailVerifyNeeded)
      case 0 => throw new Exception("Failed to set new email, account was not found")
      case x => throw new Exception("Failed to set new email, found " + x + " accounts")
    }
  }

  def setEmailVerifyNeeded(username: Username, emailVerifyNeeded: Option[Auth]): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.emailVerifyNeeded).update(emailVerifyNeeded)
    db.run(query).map {
      case 1 => ()
      case 0 => throw new Exception("Failed to set email verification status, account was not found")
      case x => throw new Exception("Failed to set email verification status, found " + x + " accounts")
    }
  }

  def refreshLastLogin(username: Username): Future[Unit] = {
    val lowercaseName = username.toLowerCase
    val now = Timestamp.get
    val query: DBIO[Int] = Accounts.table.filter(_.lowercaseName === lowercaseName).map(_.lastLogin).update(now)
    db.run(DBIO.seq(query))
  }

  def getGameStatsQuery(username: Username): DBIO[Option[AccountGameStats]] = {
    val lowercaseName = username.toLowerCase
    Accounts.table.filter(_.lowercaseName === lowercaseName).map{
      a => (a.numGamesGold, a.numGamesSilv, a.numGamesWon, a.numGamesLost, a.rating, a.ratingStdev)
    }.result.map(_.headOption).map {
      case None => None
      case Some(result) => Some(AccountGameStats.ofDB(result))
    }
  }

  def setGameStatsQuery(username: Username, stats: AccountGameStats): DBIO[Int] = {
    val lowercaseName = username.toLowerCase
    Accounts.table.filter(_.lowercaseName === lowercaseName).map{
      a => (a.numGamesGold, a.numGamesSilv, a.numGamesWon, a.numGamesLost, a.rating, a.ratingStdev)
    }.update(AccountGameStats.toDB(stats))
  }

  //Tries updating the game stats a few times, then fails
  def updateGameStats(username: Username)(f:(AccountGameStats => AccountGameStats)): Future[Unit] = {
    Utils.withRetry(AccountGameStats.updateRetryDelays,scheduler) {
      val query: DBIO[Int] =
        getGameStatsQuery(username).flatMap {
          case None => DBIO.failed(new Exception("User " + username + " not found when updating game stats"))
          case Some(stats) =>
            //Map stats with the user's provided f
            val newStats = f(stats)
            //Write the result back
            setGameStatsQuery(username,newStats)
        }.transactionally
      db.run(DBIO.seq(query))
    }
  }

  //Tries updating the game stats for a pair of players together a few times, then fails
  def updateGameStats2(username1: Username, username2: Username)
    (f: (AccountGameStats,AccountGameStats) => (AccountGameStats,AccountGameStats)): Future[Unit] = {
    Utils.withRetry(AccountGameStats.updateRetryDelays,scheduler) {
      val query: DBIO[Unit] =
        getGameStatsQuery(username1).flatMap {
          case None => DBIO.failed(new Exception("User " + username1 + " not found when updating game stats"))
          case Some(stats1) =>
            getGameStatsQuery(username2).flatMap {
              case None => DBIO.failed(new Exception("User " + username2 + " not found when updating game stats"))
              case Some(stats2) =>
                //Map stats with the user's provided f
                val (newStats1,newStats2) = f(stats1,stats2)
                //Write the result back
                DBIO.seq(
                  setGameStatsQuery(username1,newStats1),
                  setGameStatsQuery(username2,newStats2)
                )
            }
        }.transactionally
      db.run(DBIO.seq(query))
    }
  }

  //Returns a list of strings that should be displayed to a user as various notifications
  def getNotifications(username: Username): Future[List[String]] = {
    getByName(username, excludeGuests=false).map {
      case None => throw new Exception("Unknown username/account.")
      case Some(account) =>
        account.emailVerifyNeeded match {
          case None => List()
          case Some(_) => List("An email was sent to verify the address you provided. Please follow the link provided in the email to complete your registration. Otherwise, your account may be dropped after a few days. See " + domainName + "/resendVerifyEmail" + " to resend this email.")
        }
    }
  }



}

class AccountTable(tag: Tag) extends Table[Account](tag, "accountTable") {
  def lowercaseName : Rep[String] = column[String]("lowercaseName")
  def username : Rep[String] = column[String]("username")
  def email : Rep[String] = column[String]("email")
  def emailVerifyNeeded : Rep[Option[String]] = column[Option[String]]("emailVerifyNeeded")
  def passwordHash : Rep[String] = column[String]("passwordHash")
  def isBot : Rep[Boolean] = column[Boolean]("isBot")
  def createdTime : Rep[Double] = column[Double]("createdTime")
  def isGuest : Rep[Boolean] = column[Boolean]("isGuest")
  def isAdmin : Rep[Boolean] = column[Boolean]("isAdmin")
  def lastLogin : Rep[Double] = column[Double]("lastLogin")
  def numGamesGold : Rep[Int] = column[Int]("numGamesGold")
  def numGamesSilv : Rep[Int] = column[Int]("numGamesSilv")
  def numGamesWon : Rep[Int] = column[Int]("numGamesWon")
  def numGamesLost : Rep[Int] = column[Int]("numGamesLost")
  def rating : Rep[Double] = column[Double]("rating")
  def ratingStdev : Rep[Double] = column[Double]("ratingStdev")
  def priorRating : Rep[Double] = column[Double]("priorRating")
  def priorRatingStdev : Rep[Double] = column[Double]("priorRatingStdev")

  def * : ProvenShape[Account] =
    (lowercaseName, username, email, emailVerifyNeeded, passwordHash, isBot, createdTime, isGuest, isAdmin, lastLogin,
      (numGamesGold, numGamesSilv, numGamesWon, numGamesLost, rating, ratingStdev),
      priorRating, priorRatingStdev).shaped <> (
    //Database shape -> Scala object
    { case (lowercaseName, username, email, emailVerifyNeeded, passwordHash, isBot, createdTime, isGuest, isAdmin, lastLogin, gameStats, priorRating, priorRatingStdev) =>
      Account(lowercaseName, username, email, emailVerifyNeeded, passwordHash, isBot, createdTime, isGuest, isAdmin, lastLogin,
        AccountGameStats.ofDB(gameStats), Rating(priorRating, priorRatingStdev)
      )
    },
    //Scala object -> Database shape
    { a: Account =>
      Some((
        a.lowercaseName,a.username,a.email,a.emailVerifyNeeded,a.passwordHash,a.isBot,a.createdTime,a.isGuest,a.isAdmin,a.lastLogin,
        AccountGameStats.toDB(a.gameStats), a.priorRating.mean, a.priorRating.stdev
      ))
    }
  )
}
