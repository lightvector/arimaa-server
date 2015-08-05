package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.RandGen.Auth
import org.playarimaa.server.Accounts.Import._
import org.mindrot.jbcrypt.BCrypt

object SiteLogin {

  object Constants {
    val INACTIVITY_TIMEOUT: Double = 3600 * 24 * 2 //2 days

    val USERNAME_MIN_LENGTH: Int = 3
    val USERNAME_MAX_LENGTH: Int = 24
    val USERNAME_LENGTH_ERROR: String = "Username must be from 3-24 characters long."

    val USERNAME_CHARS: Set[Char] = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ List('_','-')).toSet
    val USERNAME_CHAR_ERROR: String = "Username must contain only characters in [a-zA-Z0-9_-]"

    val EMAIL_MIN_LENGTH: Int = 3
    val EMAIL_MAX_LENGTH: Int = 128
    val EMAIL_LENGTH_ERROR: String = "Email must be from 3-128 characters long."

    val PASSWORD_MIN_LENGTH: Int = 6
    val PASSWORD_MAX_LENGTH: Int = 256
    val PASSWORD_LENGTH_ERROR: String = "Password must be from 6-256 characters long."

    val NO_LOGIN_MESSAGE = "Not logged in, or timed out due to inactivity"
  }
}

import SiteLogin.Constants._

class SiteLogin(val accounts: Accounts)(implicit ec: ExecutionContext) {

  val logins: LoginTracker = new LoginTracker(INACTIVITY_TIMEOUT)

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
  def register(username: Username, email: Email, password: String, isBot: Boolean): Future[Auth] = {
    Future(()).flatMap { case () =>
      if(username.length < USERNAME_MIN_LENGTH || username.length > USERNAME_MAX_LENGTH)
        throw new IllegalArgumentException(USERNAME_LENGTH_ERROR)
      if(!username.forall(USERNAME_CHARS.contains(_)))
        throw new IllegalArgumentException(USERNAME_CHAR_ERROR)
      validateEmail(email)
      validatePassword(password)

      val lowercaseName = username.toLowerCase
      val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt)
      val now = Timestamp.get
      val createdTime = now
      val account = Account(lowercaseName,username,email,passwordHash,isBot,createdTime)
      accounts.add(account).recover { case _ => throw new Exception("Username already in use") }.map { case () =>
        logins.doTimeouts(now)
        val auth = logins.login(account.username, now)
        auth
      }
    }
  }

  //TODO throttle login attempt rate
  def login(usernameOrEmail: String, password: String): Future[Auth] = {
    accounts.getByNameOrEmail(usernameOrEmail).map { account =>
      def fail = throw new IllegalArgumentException("Invalid username/email and password combination.")
      account match {
        //Note: the quick failure here means that we're vulnerable to a time-measuring attack if we wanted
        //to keep the existence of accounts anonymous.
        case None => fail
        case Some(account) =>
          //TODO do this in another thread to limit cost?
          if(!BCrypt.checkpw(password, account.passwordHash))
            fail
          val now = Timestamp.get
          logins.doTimeouts(now)
          val auth = logins.login(account.username, now)
          auth
      }
    }
  }

  def requiringLogin[T](username: Username, auth: Auth)(f:() => T) : Try[T] = {
    val now = Timestamp.get
    logins.doTimeouts(now)
    if(logins.heartbeat(username,auth,now))
      Failure(new Exception(NO_LOGIN_MESSAGE))
    else
      Success(f())
  }

  def logout(username: Username, auth: Auth) : Try[Unit] = {
    requiringLogin(username,auth) { () =>
      logins.logout(username,auth,Timestamp.get)
    }
  }

}
