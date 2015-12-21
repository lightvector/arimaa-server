package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt, DurationDouble}
import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import org.slf4j.{Logger, LoggerFactory}
import org.mindrot.jbcrypt.BCrypt
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._
import akka.actor.{Scheduler,Cancellable}

//TODO add logging and throttling/bucketing to everything here

object SiteLogin {

  object Constants {
    val INACTIVITY_TIMEOUT: Double = 180 //3 minutes (including heartbeats)
    val PASSWORD_RESET_TIMEOUT: Double = 1800 //30 minutes
    val EMAIL_CHANGE_TIMEOUT: Double = 84600 //1 day

    //Rate of refresh of simple user infos (displayed ratings and such)
    val REFRESH_PERIOD: Double = 10 //10 seconds
    val REFRESH_PERIOD_IF_ERROR: Double = 120 //2 minutes

    val USERNAME_MIN_LENGTH: Int = 3
    val USERNAME_MAX_LENGTH: Int = 24
    val USERNAME_LENGTH_ERROR: String = "Username must be from 3-24 characters long."

    val USERNAME_CHARS: Set[Char] = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ List('_','-')).toSet
    val USERNAME_FIRST_CHARS: Set[Char] = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toSet
    val USERNAME_CHAR_ERROR: String = "Username must contain only characters in [a-zA-Z0-9_-]"
    val USERNAME_FIRST_CHAR_ERROR: String = "Username must begin with a characters in [a-zA-Z0-9]"

    val EMAIL_MIN_LENGTH: Int = 3
    val EMAIL_MAX_LENGTH: Int = 128
    val EMAIL_LENGTH_ERROR: String = "Email must be from 3-128 characters long."

    val PASSWORD_MIN_LENGTH: Int = 6
    val PASSWORD_MAX_LENGTH: Int = 256
    val PASSWORD_LENGTH_ERROR: String = "Password must be from 6-256 characters long."

    val NO_LOGIN_MESSAGE: String = "Not logged in, or timed out due to inactivity"

    val INVALID_USERNAME_ERROR: String = "Invalid username or username already in use"
    val INVALID_USERNAMES = List(
      "anyone",
      "admin",
      "root",
      "guest",
      "user",
      "test",
      "administrator",
      "moderator"
    )
  }
}

case class AuthTime(auth: Auth, time: Timestamp)

import SiteLogin.Constants._

class SiteLogin(val accounts: Accounts, val emailer: Emailer, val cryptEC: ExecutionContext, val scheduler: Scheduler)(implicit ec: ExecutionContext) {

  val logins: LoginTracker = new LoginTracker(None, INACTIVITY_TIMEOUT, updateInfosFromParent = false)

  var passResets: Map[Username,AuthTime] = Map()
  val passResetLock = new Object()

  var emailChanges: Map[Username,(AuthTime,Email)] = Map()
  val emailChangeLock = new Object()

  val logger =  LoggerFactory.getLogger(getClass)

  //Begin loop on initialization
  refreshLoginInfosLoop()

  def validateUsername(username: Username): Unit = {
    if(username.length < USERNAME_MIN_LENGTH || username.length > USERNAME_MAX_LENGTH)
      throw new IllegalArgumentException(USERNAME_LENGTH_ERROR)
    if(!username.forall(USERNAME_CHARS.contains(_)))
      throw new IllegalArgumentException(USERNAME_CHAR_ERROR)
    if(!USERNAME_FIRST_CHARS.contains(username(0)))
      throw new IllegalArgumentException(USERNAME_FIRST_CHAR_ERROR)
    val lowercaseName = username.toLowerCase
    if(INVALID_USERNAMES.exists(_ == lowercaseName))
      throw new IllegalArgumentException(INVALID_USERNAME_ERROR)
  }

  def validateEmail(email: Email): Unit = {
    if(!email.contains('@'))
      throw new IllegalArgumentException("Email must contain an '@'.")
    if(email.length < EMAIL_MIN_LENGTH || EMAIL_MAX_LENGTH > EMAIL_MAX_LENGTH)
      throw new IllegalArgumentException(EMAIL_LENGTH_ERROR)
  }

  def validatePassword(password: String): Unit = {
    if(password.length < PASSWORD_MIN_LENGTH || PASSWORD_MAX_LENGTH > PASSWORD_MAX_LENGTH)
      throw new IllegalArgumentException(PASSWORD_LENGTH_ERROR)
  }

  def doTimeouts(now: Timestamp): Unit = {
    val usersTimedOut = logins.doTimeouts(now)
    usersTimedOut.foreach { user =>
      accounts.removeIfGuest(user)
    }
  }

  private def refreshLoginInfos() : Future[Unit] = {
    doTimeouts(Timestamp.get)
    val futures = logins.usersLoggedIn.map { user =>
      accounts.getByName(user.name,excludeGuests=false).map {
        case None => ()
        case Some(acct) =>logins.updateInfo(acct.info)
      }
    }
    Future.sequence(futures).map { _ : List[Unit] => () }
  }

  private def refreshLoginInfosLoop(): Unit = {
    refreshLoginInfos().onComplete { result =>
      val nextDelay =
        result match {
          case Failure(exn) =>
            logger.error("Error in refreshLoginInfosLoop: " + exn)
            REFRESH_PERIOD_IF_ERROR
          case Success(()) =>
            REFRESH_PERIOD
        }
      try {
        scheduler.scheduleOnce(nextDelay seconds) { refreshLoginInfosLoop() }
      }
      catch {
        //Thrown when the actorsystem shuts down, ignore
        case _ : IllegalStateException => ()
      }
    }
  }


  def usersLoggedIn: List[SimpleUserInfo] = {
    val now = Timestamp.get
    doTimeouts(now)
    logins.usersLoggedIn
  }

  //TODO throttle registrations somehow?
  def register(username: Username, email: Email, password: String, isBot: Boolean, priorRating:Option[Double]): Future[(Username,SiteAuth)] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)
      validateEmail(email)
      validatePassword(password)
      priorRating.foreach(_.validateNonNegative("priorRating"))

      val lowercaseName = username.toLowerCase
      val rating = priorRating match {
        case None => Rating.newPlayerPrior
        case Some(x) => Rating.givenRatingPrior(x)
      }

      Future(BCrypt.hashpw(password, BCrypt.gensalt))(cryptEC).flatMap { passwordHash =>
        val now = Timestamp.get
        val account = Account(
          lowercaseName,
          username,
          email,
          passwordHash,
          isBot,
          createdTime = now,
          isGuest = false,
          lastLogin = now,
          gameStats = AccountGameStats.initial(rating),
          priorRating = rating
        )
        accounts.add(account).recover { case _ => throw new Exception(INVALID_USERNAME_ERROR) }.map { case () =>
          doTimeouts(now)
          val siteAuth = logins.login(account.info, now)
          (account.username,siteAuth)
        }
      }
    }
  }

  //TODO throttle login attempt rate
  def login(usernameOrEmail: String, password: String): Future[(Username,SiteAuth)] = {
    accounts.getByNameOrEmail(usernameOrEmail, excludeGuests=true).flatMap { accounts =>
      def fail = throw new IllegalArgumentException("Invalid username/email and password combination.")

      //Note: with this implementation, we would be vulnerable to a time-measuring attack if we wanted
      //to keep the existence of accounts anonymous.
      val accountMatched: List[Future[(Account,Boolean)]] = accounts.map { account =>
        Future(BCrypt.checkpw(password, account.passwordHash))(cryptEC).map { success => (account,success) }
      }
      Future.sequence(accountMatched).recover { case _ =>
        //TODO log this error
        fail
      }.map { accountMatched =>
        val matchingAccounts = accountMatched.flatMap { case (account,matched) =>
          if(matched) Some(account) else None
        }
        matchingAccounts match {
          //0 matches
          case Nil => fail
          //1 match
          case account :: Nil =>
            val now = Timestamp.get
            doTimeouts(now)
            val siteAuth = logins.login(account.info, now)
            (account.username,siteAuth)
          //more matches
          case _ =>
            val users = matchingAccounts.map(_.username)
            throw new Exception("Multiple users with that email/password combination: " + users.mkString(" "))
        }
      }
    }
  }

  def loginGuest(username: Username): Future[(Username,SiteAuth)] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)

      val lowercaseName = username.toLowerCase
      val now = Timestamp.get
      val rating = Rating.newPlayerPrior
      val account = Account(
        lowercaseName,
        username,
        email = "",
        passwordHash = "N/A",
        isBot = false,
        createdTime = now,
        isGuest = true,
        lastLogin = now,
        gameStats = AccountGameStats.initial(rating),
        priorRating = rating
      )
      accounts.add(account).recover { case _ => throw new Exception(INVALID_USERNAME_ERROR) }.map { case () =>
        doTimeouts(now)
        val siteAuth = logins.login(account.info, now)
        (account.username,siteAuth)
      }
    }
  }

  def requiringLogin[T](siteAuth: SiteAuth)(f:SimpleUserInfo => T) : Try[T] = {
    val now = Timestamp.get
    doTimeouts(now)
    logins.heartbeatAuth(siteAuth,now) match {
      case None => Failure(new Exception(NO_LOGIN_MESSAGE))
      case Some(username) => Success(f(username))
    }
  }

  def logout(siteAuth: SiteAuth) : Try[Unit] = {
    requiringLogin(siteAuth) { _ =>
      val now = Timestamp.get
      logins.userOfAuth(siteAuth) match {
        case None =>
          logins.logoutAuth(siteAuth,now)
        case Some(user) =>
          logins.logoutUser(user.name,now)
          accounts.removeIfGuest(user.name)
          ()
      }
    }
  }

  def isAuthLoggedIn(siteAuth: SiteAuth) : Boolean = {
    val now = Timestamp.get
    doTimeouts(now)
    logins.isAuthLoggedIn(siteAuth)
  }

  def isAuthLoggedInAndHeartbeat(siteAuth: SiteAuth) : Boolean = {
    val now = Timestamp.get
    doTimeouts(now)
    logins.heartbeatAuth(siteAuth,now)
    logins.isAuthLoggedIn(siteAuth)
  }

  def forgotPassword(usernameOrEmail: Username) : Unit = {
    //Spawn off a job to find the user's account if it exists and send the email and don't wait for it
    accounts.getByNameOrEmail(usernameOrEmail, excludeGuests=true).onComplete { result =>
      result match {
        case Failure(_) =>
          //TODO log the failure
          ()
        case Success(Nil) =>
          //Looks email-like?
          if(usernameOrEmail.contains('@'))
            emailer.sendPasswordResetNoAccount(usernameOrEmail)
        case Success(results) =>
          results.foreach { account =>
            val auth = RandGen.genAuth
            passResetLock.synchronized {
              val now = Timestamp.get
              //Filter all reset tokens that are too old
              passResets = passResets.filter { case (user,authTime) =>
                now - authTime.time < PASSWORD_RESET_TIMEOUT
              }
              //Add the new reset key
              passResets = passResets + (account.username -> AuthTime(auth, Timestamp.get))
            }
            //Send email to user advising about reset
            emailer.sendPasswordResetRequest(account.email,account.username,auth)
          }
      }
    }
  }

  def resetPassword(usernameOrEmail: Username, resetAuth: Auth, password: String) : Future[Unit] = {
    validatePassword(password)
    accounts.getByNameOrEmail(usernameOrEmail, excludeGuests=true).flatMap { matchingAccounts =>
      def fail = throw new IllegalArgumentException("Unknown username/email.")
      matchingAccounts match {
        case Nil => fail
        case account :: Nil =>
          val authTime = passResetLock.synchronized { passResets.get(account.username) }
          val now = Timestamp.get
          def expired = throw new Exception("Password was NOT reset - forgotten password request expired. Please try requesting again.")
          authTime match {
            case None => expired
            case Some(authTime) =>
              if(now - authTime.time >= PASSWORD_RESET_TIMEOUT || resetAuth != authTime.auth)
                expired
              //Expire all reset tokens for this user
              passResetLock.synchronized {
                passResets = passResets.filter { case (user,authTime) =>
                  user != account.username
                }
              }
              Future(BCrypt.hashpw(password, BCrypt.gensalt))(cryptEC).flatMap { passwordHash =>
                accounts.setPasswordHash(account.username,passwordHash)
              }
          }
        case _ =>
          val users = matchingAccounts.map(_.username)
          throw new Exception("Multiple users with that email/password combination: " + users.mkString(" "))
      }
    }
  }


  def changePassword(username: Username, password: String, siteAuth: SiteAuth, newPassword: String) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validatePassword(newPassword)
      requiringLogin(siteAuth) { user =>
        accounts.getByName(username, excludeGuests=true).flatMap { result =>
          result match {
            case None => throw new IllegalArgumentException("Unknown username.")
            case Some(account) =>
              if(user.name != account.username)
                throw new Exception("Username does not match login.")

              Future(BCrypt.checkpw(password, account.passwordHash))(cryptEC).flatMap { success =>
                if(!success)
                  throw new Exception("Old password did not match.")
                Future(BCrypt.hashpw(newPassword, BCrypt.gensalt))(cryptEC).flatMap { passwordHash =>
                  accounts.setPasswordHash(account.username,passwordHash)
                }
              }
          }
        }
      }.get
    }
  }

  def changeEmail(username: Username, password: String, siteAuth: SiteAuth, newEmail: Email) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validateEmail(newEmail)
      requiringLogin(siteAuth) { user =>
        accounts.getByName(username, excludeGuests=true).flatMap { result =>
          result match {
            case None => throw new IllegalArgumentException("Unknown username.")
            case Some(account) =>
              if(user.name != account.username)
                throw new Exception("Username does not match login.")
              if(newEmail == account.email)
                throw new Exception("New email is the same as the old email.")

              Future(BCrypt.checkpw(password, account.passwordHash))(cryptEC).flatMap { success =>
                if(!success)
                  throw new Exception("Password did not match.")
                val auth = RandGen.genAuth
                emailChangeLock.synchronized {
                  val now = Timestamp.get
                  //Filter all reset tokens that are too old
                  emailChanges = emailChanges.filter { case (user,(authTime,_)) =>
                    now - authTime.time < EMAIL_CHANGE_TIMEOUT
                  }
                  //Add the new reset key
                  val value = (AuthTime(auth, Timestamp.get),newEmail)
                  emailChanges = emailChanges + (account.username -> value)
                }
                //Send email to user advising about change
                emailer.sendEmailChangeRequest(newEmail,account.username,auth,account.email)
              }
          }
        }
      }.get
    }
  }

  def confirmChangeEmail(username: Username, changeAuth: SiteAuth) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      accounts.getByName(username, excludeGuests=true).flatMap { result =>
        result match {
          case None => throw new IllegalArgumentException("Unknown username.")
          case Some(account) =>

            val authTimeAndNewEmail = emailChangeLock.synchronized { emailChanges.get(account.username) }
            val now = Timestamp.get
            def expired = throw new Exception("Email was NOT changed - request expired. Please try requesting again.")
            authTimeAndNewEmail match {
              case None => expired
              case Some((authTime,newEmail)) =>
                if(now - authTime.time >= EMAIL_CHANGE_TIMEOUT || changeAuth != authTime.auth)
                  expired
                //Expire all reset tokens for this user
                emailChangeLock.synchronized {
                  emailChanges = emailChanges.filter { case (user,(authTime,_)) =>
                    user != account.username
                  }
                }
                accounts.setEmail(account.username,newEmail)
                emailer.sendOldEmailChangeNotification(account.email,account.username,newEmail)
            }
        }
      }
    }
  }

}
