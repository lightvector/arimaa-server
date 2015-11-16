package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}
import org.mindrot.jbcrypt.BCrypt
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp

//TODO add logging and throttling/bucketing to everything here

object SiteLogin {

  object Constants {
    val INACTIVITY_TIMEOUT: Double = 3600 * 24 * 2 //2 days
    val PASSWORD_RESET_TIMEOUT: Double = 1800 //30 minutes

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

case class PasswordReset(auth: Auth, time: Timestamp)

import SiteLogin.Constants._

class SiteLogin(val accounts: Accounts, val emailer: Emailer, val cryptEC: ExecutionContext)(implicit ec: ExecutionContext) {

  val logins: LoginTracker = new LoginTracker(None,INACTIVITY_TIMEOUT)

  var passResets: Map[Username,PasswordReset] = Map()
  val passResetLock = new Object()

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

  //TODO throttle registrations somehow?
  def register(username: Username, email: Email, password: String, isBot: Boolean): Future[(Username,SiteAuth)] = {
    Future.successful(()).flatMap { case () =>
      if(username.length < USERNAME_MIN_LENGTH || username.length > USERNAME_MAX_LENGTH)
        throw new IllegalArgumentException(USERNAME_LENGTH_ERROR)
      if(!username.forall(USERNAME_CHARS.contains(_)))
        throw new IllegalArgumentException(USERNAME_CHAR_ERROR)
      if(!USERNAME_FIRST_CHARS.contains(username(0)))
        throw new IllegalArgumentException(USERNAME_FIRST_CHAR_ERROR)
      validateEmail(email)
      validatePassword(password)

      val lowercaseName = username.toLowerCase
      if(INVALID_USERNAMES.exists(_ == lowercaseName))
        throw new IllegalArgumentException(INVALID_USERNAME_ERROR)

      Future(BCrypt.hashpw(password, BCrypt.gensalt))(cryptEC).flatMap { passwordHash =>
        val now = Timestamp.get
        val createdTime = now
        val account = Account(lowercaseName,username,email,passwordHash,isBot,createdTime)
        accounts.add(account).recover { case _ => throw new Exception(INVALID_USERNAME_ERROR) }.map { case () =>
          logins.doTimeouts(now)
          val siteAuth = logins.login(account.username, now)
          (account.username,siteAuth)
        }
      }
    }
  }

  //TODO throttle login attempt rate
  def login(usernameOrEmail: String, password: String): Future[(Username,SiteAuth)] = {
    accounts.getByNameOrEmail(usernameOrEmail).flatMap { account =>
      def fail = throw new IllegalArgumentException("Invalid username/email and password combination.")
      account match {
        //Note: the quick failure here means that we're vulnerable to a time-measuring attack if we wanted
        //to keep the existence of accounts anonymous.
        case None => fail
        case Some(account) =>
          Future(BCrypt.checkpw(password, account.passwordHash))(cryptEC).map { success =>
            if(!success)
              fail
            val now = Timestamp.get
            logins.doTimeouts(now)
            val siteAuth = logins.login(account.username, now)
            (account.username,siteAuth)
          }
      }
    }
  }

  def requiringLogin[T](siteAuth: SiteAuth)(f:Username => T) : Try[T] = {
    val now = Timestamp.get
    logins.doTimeouts(now)
    logins.heartbeatAuth(siteAuth,now) match {
      case None => Failure(new Exception(NO_LOGIN_MESSAGE))
      case Some(username) => Success(f(username))
    }
  }

  def logout(siteAuth: SiteAuth) : Try[Unit] = {
    requiringLogin(siteAuth) { _ =>
      logins.logoutAuth(siteAuth,Timestamp.get)
    }
  }

  def isAuthLoggedIn(siteAuth: SiteAuth) : Boolean = {
    val now = Timestamp.get
    logins.doTimeouts(now)
    logins.isAuthLoggedIn(siteAuth)
  }

  def forgotPassword(usernameOrEmail: Username) : Unit = {
    //Spawn off a job to find the user's account if it exists and send the email and don't wait for it
    accounts.getByNameOrEmail(usernameOrEmail).onComplete { result =>
      result match {
        case Failure(_) =>
          //TODO log the failure
          ()
        case Success(None) =>
          //Looks email-like?
          if(usernameOrEmail.contains('@'))
            emailer.sendPasswordResetNoAccount(usernameOrEmail)
        case Success(Some(account)) =>
          val auth = RandGen.genAuth
          passResetLock.synchronized {
            val now = Timestamp.get
            //Filter all reset tokens that are too old
            passResets = passResets.filter { case (user,resetInfo) =>
              now - resetInfo.time < PASSWORD_RESET_TIMEOUT
            }
            //Add the new reset key
            passResets = passResets + (account.username -> PasswordReset(auth, Timestamp.get))
          }
          //Send email to user advising about reset
          emailer.sendPasswordResetRequest(account.email,account.username,auth)
      }
    }
  }

  def resetPassword(usernameOrEmail: Username, resetAuth: Auth, password: String) : Future[Unit] = {
    validatePassword(password)
    accounts.getByNameOrEmail(usernameOrEmail).flatMap { account =>
      def fail = throw new IllegalArgumentException("Unknown username/email.")
      account match {
        case None => fail
        case Some(account) =>
          val resetInfo = passResetLock.synchronized { passResets.get(account.username) }
          val now = Timestamp.get
          def expired = throw new Exception("Password was NOT reset - forgotten password request expired. Please try requesting again.")
          resetInfo match {
            case None => expired
            case Some(resetInfo) =>
              if(now - resetInfo.time >= PASSWORD_RESET_TIMEOUT || resetAuth != resetInfo.auth)
                expired
              //Expire all reset tokens for this user
              passResetLock.synchronized {
                passResets = passResets.filter { case (user,resetInfo) =>
                  user != account.username
                }
              }
              Future(BCrypt.hashpw(password, BCrypt.gensalt))(cryptEC).flatMap { passwordHash =>
                accounts.setPasswordHash(account.username,passwordHash)
              }
          }
      }
    }
  }

}
