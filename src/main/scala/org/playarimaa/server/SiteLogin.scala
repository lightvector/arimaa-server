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

object SiteLogin {

  object Constants {
    //How much inactivity until a user is no longer displayed as present in the game room, including heartbeats.
    val INACTIVITY_TIMEOUT: Double = 120 //2 minutes
    //How much inactivity until we actually invalidate the user's authentication and require a re-log-in
    val LOGOUT_TIMEOUT: Double = 172800 //2 days
    //How long a user can be logged in total until they get logged out the moment they're inactive, including heartbeats.
    val SOFT_LOGIN_TIME_LIMIT: Double = 172800 //2 days

    //Timeouts for resetting passwords and email change requests
    val PASSWORD_RESET_TIMEOUT: Double = 1800 //30 minutes
    val EMAIL_CHANGE_TIMEOUT: Double = 86400 //1 day

    //Rate of refresh of simple user infos (displayed ratings and such), as well as internal upkeep and cleanup
    val LOOP_PERIOD: Double = 10 //10 seconds
    val LOOP_PERIOD_IF_ERROR: Double = 120 //2 minutes

    //Rate of checking and deleting users whose emails are not verified
    val VERIFY_EMAIL_LOOP_PERIOD: Double = 42300 //12 hours
    val DELETE_UNVERIFIED_ACCOUNTS_OLDER_THAN: Double = 86400 * 2 //2 days

    //Allow 10 login attempts in a row, refilling at a rate of 1 per minute
    val LOGIN_BUCKET_CAPACITY: Double = 10
    val LOGIN_BUCKET_FILL_PER_SEC: Double = 1.0/60.0

    //Allow 5 forget password sends in a row, refilling at a rate of 1 per minute
    val FORGOT_PASS_BUCKET_CAPACITY: Double = 5
    val FORGOT_PASS_BUCKET_FILL_PER_SEC: Double = 1.0/60.0

    //Allow 15 queries that change account stuff in a row, refilling at a rate of 1 per minute
    val ACCOUNT_STUFF_BUCKET_CAPACITY: Double = 15
    val ACCOUNT_STUFF_BUCKET_FILL_PER_SEC: Double = 1.0/60.0

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

    val NO_LOGIN_MESSAGE: String = "Not logged in, or timed out due to inactivity."

    val INTERNAL_ERROR: String = "Internal server error."
    val LOGIN_ERROR: String = "Invalid username/email and password combination."

    //These are also used to filter out some email addresses as a slight speedbump against
    //some forms of spammy stuff according to AWS email best practices - these include a variety
    //of "role" accounts.
    val INVALID_USERNAME_ERROR: String = "Invalid username or username already in use."
    val INVALID_USERNAMES: List[String] = List(
      "abuse",
      "admin",
      "administrator",
      "all",
      "anyone",
      "billing",
      "contact",
      "compliance",
      "devnull",
      "dns",
      "everyone",
      "feedback",
      "ftp",
      "guest",
      "help",
      "hostmaster",
      "info",
      "inoc",
      "inquiries",
      "investorrelations",
      "ispfeedback",
      "ispsupport",
      "jobs",
      "list",
      "listrequest",
      "maildaemon",
      "marketing",
      "media",
      "moderator",
      "news",
      "nobody",
      "noc",
      "none",
      "noreply",
      "null",
      "orders",
      "phish",
      "phishing",
      "postmaster",
      "press",
      "privacy",
      "registrar",
      "remove",
      "root",
      "sales",
      "security",
      "service",
      "spam",
      "support",
      "sysadmin",
      "tech",
      "test",
      "trouble",
      "undefined",
      "undisclosedrecipients",
      "unsubscribe",
      "usenet",
      "user",
      "uucp",
      "webmaster",
      "www"
    )
    val INVALID_EMAIL_ERROR: String = "Invalid email address, please try a different one."
    val INVALID_EMAILS: List[String] = INVALID_USERNAMES
  }
}

case class AuthTime(auth: Auth, time: Timestamp)

import SiteLogin.Constants._

class SiteLogin(val accounts: Accounts, val emailer: Emailer, val cryptEC: ExecutionContext, val scheduler: Scheduler)(implicit ec: ExecutionContext) {

  val logins: LoginTracker = new LoginTracker(None, INACTIVITY_TIMEOUT, LOGOUT_TIMEOUT, SOFT_LOGIN_TIME_LIMIT, updateInfosFromParent = false)

  //Throttles for different kinds of queries
  private val loginBuckets: TimeBuckets[String] = new TimeBuckets(LOGIN_BUCKET_CAPACITY, LOGIN_BUCKET_FILL_PER_SEC)
  private val forgotPassBuckets: TimeBuckets[String] = new TimeBuckets(FORGOT_PASS_BUCKET_CAPACITY, FORGOT_PASS_BUCKET_FILL_PER_SEC)
  private val accountStuffBuckets: TimeBuckets[String] = new TimeBuckets(ACCOUNT_STUFF_BUCKET_CAPACITY, ACCOUNT_STUFF_BUCKET_FILL_PER_SEC)

  private var passResets: Map[Username,AuthTime] = Map()
  private val passResetLock = new Object()

  private var emailChanges: Map[Username,(AuthTime,Email)] = Map()
  private val emailChangeLock = new Object()

  val logger =  LoggerFactory.getLogger(getClass)

  //Begin loops on initialization
  upkeepLoop()
  verifyLoop()

  private def upkeepLoop(): Unit = {
    val now = Timestamp.get

    //Refresh login infos to update ratings and such displayed to users
    refreshLoginInfos().onComplete { result =>
      val nextDelay =
        result match {
          case Failure(exn) =>
            logger.error("Error in upkeepLoop: " + exn)
            LOOP_PERIOD_IF_ERROR
          case Success(()) =>
            LOOP_PERIOD
        }
      try {
        scheduler.scheduleOnce(nextDelay seconds) { upkeepLoop() }
      }
      catch {
        //Thrown when the actorsystem shuts down, ignore
        case _ : IllegalStateException => ()
      }
    }
  }

  private def verifyLoop(): Unit = {
    val now = Timestamp.get

    //Delete all accounts that have not verified emails that are sufficiently old
    accounts.getAllUnverified().flatMap { accountList =>
      val results =
        accountList.flatMap { account =>
          if(logins.isUserLoggedIn(account.username) || now > account.createdTime + DELETE_UNVERIFIED_ACCOUNTS_OLDER_THAN)
            None
          else
            Some(accounts.removeIfUnverified(account.username).map { case () =>
              logins.logoutUser(account.username,Timestamp.get)
            })
        }
      Future.sequence(results)
    }.onComplete { result =>
      result match {
        case Failure(exn) =>
          logger.error("Error in verifyLoop: " + exn)
        case Success(_ : List[Unit]) =>
          ()
      }
      try {
        scheduler.scheduleOnce(VERIFY_EMAIL_LOOP_PERIOD seconds) { upkeepLoop() }
      }
      catch {
        //Thrown when the actorsystem shuts down, ignore
        case _ : IllegalStateException => ()
      }
    }
  }


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

    //Filter down to the alphanumeric part of the email address to the left of the @
    val s = email.slice(0,email.lastIndexOf('@')).filter(_.isLetterOrDigit).toLowerCase
    if(INVALID_EMAILS.exists(_ == s))
      throw new IllegalArgumentException(INVALID_EMAIL_ERROR)
  }

  def validateUsernameOrEmail(usernameOrEmail: String) : Unit = {
    Try(validateUsername(usernameOrEmail)) match {
      case Success(()) => ()
      case Failure(exn1) =>
        Try(validateEmail(usernameOrEmail)) match {
          case Success(()) => ()
          case Failure(exn2) =>
            throw new Exception("Invalid username or email (" + exn1.getMessage() + ") (" + exn2.getMessage() + ")")
        }
    }
  }

  def validatePassword(password: String): Unit = {
    if(password.length < PASSWORD_MIN_LENGTH || PASSWORD_MAX_LENGTH > PASSWORD_MAX_LENGTH)
      throw new IllegalArgumentException(PASSWORD_LENGTH_ERROR)
  }

  def doTimeouts(now: Timestamp): Unit = {
    val usersLoggedOut = logins.doTimeouts(now)
    usersLoggedOut.foreach { user =>
      accounts.removeIfGuest(user)
    }
  }

  private def refreshLoginInfos() : Future[Unit] = {
    doTimeouts(Timestamp.get)
    val futures = logins.usersLoggedIn.map { user =>
      accounts.getByName(user.name,excludeGuests=false).map {
        case None => ()
        case Some(acct) => logins.updateInfo(acct.info)
      }
    }
    Future.sequence(futures).map { _ : List[Unit] => () }
  }

  def usersLoggedIn: List[SimpleUserInfo] = {
    val now = Timestamp.get
    doTimeouts(now)
    logins.usersActive
  }

  private def hashpw(password: String): Future[String] = {
    Future(BCrypt.hashpw(password, BCrypt.gensalt))(cryptEC).recover {
      case exn: Exception =>
        logger.error("Error when hashing password: " + exn)
        throw new Exception(INTERNAL_ERROR)
    }
  }

  private def checkpw(password: String, passwordHash: String): Future[Boolean] = {
    Future(BCrypt.checkpw(password, passwordHash))(cryptEC).recover {
      case exn: Exception =>
        logger.error("Error when checking password: " + exn)
        throw new Exception(INTERNAL_ERROR)
    }
  }

  def register(
    username: Username,
    email: Email,
    password: String,
    isBot: Boolean,
    priorRating: Option[Double],
    oldSiteAuth: Option[SiteAuth],
    logInfo: LogInfo
  ): Future[(Username,SiteAuth)] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)
      validateEmail(email)
      validatePassword(password)
      priorRating.foreach(_.validateNonNegative("priorRating"))

      logger.info(logInfo + " Attempting to register new account for " + username)

      val lowercaseName = username.toLowerCase
      val rating = priorRating match {
        case None => Rating.newPlayerPrior
        case Some(x) => Rating.givenRatingPrior(x)
      }

      hashpw(password).flatMap { passwordHash =>
        val now = Timestamp.get
        val verifyAuth = RandGen.genAuth
        val account = Account(
          lowercaseName = lowercaseName,
          username = username,
          userID = RandGen.genUserID,
          email = email,
          emailVerifyNeeded = Some(verifyAuth),
          passwordHash = passwordHash,
          isBot = isBot,
          createdTime = now,
          isGuest = false,
          isAdmin = false,
          lastLogin = now,
          gameStats = AccountGameStats.initial(rating),
          priorRating = rating
        )

        //You can't register a new account if someone (i.e. a guest) is logged in with that account,
        //UNLESS you were the one logged in with that account (with the siteAuth to prove it).
        //Note: not sensitive to capitalization of user's name
        //Slight race condition possible here, but hopefully not a big deal
        if(logins.isUserActive(username) &&
          !oldSiteAuth.exists { oldSiteAuth => logins.isLoggedIn(username,oldSiteAuth) })
          throw new Exception(INVALID_USERNAME_ERROR)

        accounts.add(account).recover { case _ => throw new Exception(INVALID_USERNAME_ERROR) }.map { case () =>
          //Mitigate race condition by afterwards logging out anything logged in with this name
          logins.logoutUser(username,now)

          logger.info(logInfo + " Registered new account for " + username)
          emailer.sendVerifyEmail(email,username,verifyAuth)

          doTimeouts(now)
          val siteAuth = logins.login(account.info, now)
          (account.username,siteAuth)
        }
      }
    }
  }

  def login(usernameOrEmail: String, password: String, logInfo: LogInfo): Future[(Username,SiteAuth)] = {
    Future.successful(()).flatMap { case () =>
      validateUsernameOrEmail(usernameOrEmail)

      //Throttle login attempts roughly by account (more specifically, by lowercase key of email/username)
      if(!loginBuckets.takeOne(usernameOrEmail.toLowerCase, Timestamp.get)) {
        logger.warn(logInfo + " Too many login attempts for " + usernameOrEmail)
        throw new Exception("Too many login attempts for account, please wait a few minutes before the next attempt.")
      }

      accounts.getByNameOrEmail(usernameOrEmail, excludeGuests=true).flatMap { accounts =>
        def fail() = throw new IllegalArgumentException(LOGIN_ERROR)

        //Note: with this implementation, we would be vulnerable to a time-measuring attack if we wanted
        //to keep the existence of accounts anonymous.
        val accountMatched: List[Future[(Account,Boolean)]] = accounts.map { account =>
          checkpw(password, account.passwordHash).map { success => (account,success) }
        }
        Future.sequence(accountMatched).map { accountMatched =>
          val matchingAccounts = accountMatched.flatMap { case (account,matched) =>
            if(matched) Some(account) else None
          }
          matchingAccounts match {
            //0 matches
            case Nil => fail()
            //1 match
            case account :: Nil =>
              val now = Timestamp.get
              doTimeouts(now)
              val siteAuth = logins.login(account.info, now)
              logger.info(logInfo + " User logged in: " + account.username)
              (account.username,siteAuth)
            //more matches
            case _ =>
              val users = matchingAccounts.map(_.username)
              throw new Exception("Multiple users with that email/password combination: " + users.mkString(" "))
          }
        }
      }
    }
  }

  def loginGuest(username: Username, oldSiteAuth: Option[SiteAuth], logInfo: LogInfo): Future[(Username,SiteAuth)] = {
    Future.successful(()).flatMap { case () =>
      if(username == "")
        throw new Exception("Please choose a username.")
      validateUsername(username)

      val lowercaseName = username.toLowerCase
      val now = Timestamp.get
      val rating = Rating.newPlayerPrior
      val account = Account(
        lowercaseName = lowercaseName,
        username = username,
        userID = RandGen.genUserID,
        email = "",
        emailVerifyNeeded = None,
        passwordHash = "N/A",
        isBot = false,
        createdTime = now,
        isGuest = true,
        isAdmin = false,
        lastLogin = now,
        gameStats = AccountGameStats.initial(rating),
        priorRating = rating
      )

      //Also only allow guest login if nobody is already logged in with that account.
      //UNLESS you were the one logged in with that account (with the siteAuth to prove it).
      //Note: not sensitive to capitalization of user's name
      //Slight race condition possible here, but hopefully not a big deal
      if(logins.isUserActive(username) &&
        !oldSiteAuth.exists { oldSiteAuth => logins.isLoggedIn(username,oldSiteAuth) })
        throw new Exception(INVALID_USERNAME_ERROR)

      accounts.add(account).recover { case _ => throw new Exception(INVALID_USERNAME_ERROR) }.map { case () =>
        //Mitigate race condition by afterwards logging out anything logged in with this name
        logins.logoutUser(username,now)

        doTimeouts(now)
        val siteAuth = logins.login(account.info, now)
        logger.info(logInfo + " Guest logged in: " + account.username)
        (account.username,siteAuth)
      }
    }
  }

  def requiringLogin[T](siteAuth: SiteAuth)(f:SimpleUserInfo => T) : Try[T] = {
    val now = Timestamp.get
    doTimeouts(now)
    logins.heartbeatAuth(siteAuth,now) match {
      case None => Failure(new Exception(NO_LOGIN_MESSAGE))
      case Some(user) => Success(f(user))
    }
  }

  def logout(siteAuth: SiteAuth, logInfo: LogInfo) : Try[Unit] = {
    requiringLogin(siteAuth) { _ =>
      val now = Timestamp.get
      logins.userOfAuth(siteAuth) match {
        case None =>
          logins.logoutAuth(siteAuth,now)
        case Some(user) =>
          logins.logoutUser(user.name,now)
          accounts.removeIfGuest(user.name)
          if(user.isGuest)
            logger.info(logInfo + " Guest logged out: " + user.name)
          else
            logger.info(logInfo + " User logged out: " + user.name)
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

  def forgotPassword(usernameOrEmail: String, logInfo: LogInfo) : Future[Unit] = {
    Future.successful(()).map { case () =>
      validateUsernameOrEmail(usernameOrEmail)

      if(!forgotPassBuckets.takeOne(usernameOrEmail.toLowerCase, Timestamp.get)) {
        logger.warn(logInfo + " Too many forgot password attempts for " + usernameOrEmail)
        throw new Exception("Too many forget password attempts for account, please wait a few minutes before the next attempt.")
      }

      //Spawn off a job to find the user's account if it exists and send the email and don't wait for it
      accounts.getByNameOrEmail(usernameOrEmail, excludeGuests=true).onComplete { result =>
        result match {
          case Failure(exn) =>
            logger.error(logInfo + " Error when handling password reset request: " + exn)
            ()
          case Success(Nil) =>
            //Looks email-like?
            if(usernameOrEmail.contains('@'))
              emailer.sendPasswordResetNoAccount(usernameOrEmail)
            logger.info(logInfo + " User requested password reset for unknown account/email: " + usernameOrEmail)
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
              logger.info(logInfo + " User requested password reset: " + account.username)
            }
        }
      }
    }
  }

  def resetPassword(usernameOrEmail: String, resetAuth: Auth, password: String, logInfo: LogInfo) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validateUsernameOrEmail(usernameOrEmail)
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
                hashpw(password).flatMap { passwordHash =>
                  accounts.setPasswordHash(account.username,passwordHash).map { result =>
                    logger.info(logInfo + " Password reset for account: " + account.username)
                    result
                  }
                }
            }
          case _ =>
            val users = matchingAccounts.map(_.username)
            throw new Exception("Multiple users with that email/password combination: " + users.mkString(" "))
        }
      }.recover { case exn: Exception =>
          logger.info(logInfo + " Password reset for " + usernameOrEmail + " failed with result " + exn)
          throw exn
      }
    }
  }


  def changePassword(username: Username, password: String, siteAuth: SiteAuth, newPassword: String, logInfo: LogInfo) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)
      validatePassword(newPassword)

      requiringLogin(siteAuth) { user =>
        accounts.getByName(username, excludeGuests=true).flatMap { result =>
          result match {
            case None => throw new IllegalArgumentException("Unknown username/account.")
            case Some(account) =>
              if(user.name != account.username)
                throw new Exception("Username does not match login.")

              if(!accountStuffBuckets.takeOne(username.toLowerCase, Timestamp.get)) {
                logger.warn(logInfo + " Too many account data changes for " + username)
                throw new Exception("Too many account data changes for account, please wait a few minutes before the next attempt.")
              }

              checkpw(password, account.passwordHash).flatMap { success =>
                if(!success)
                  throw new Exception("Old password did not match.")
                hashpw(newPassword).flatMap { passwordHash =>
                  accounts.setPasswordHash(account.username,passwordHash).map { result =>
                    logger.info(logInfo + " Password changed for account: " + account.username)
                    result
                  }
                }
              }
          }
        }.recover { case exn: Exception =>
            logger.info(logInfo + " Change password for account: " + username + " failed with result " + exn)
            throw exn
        }
      }.get
    }
  }

  def verifyEmail(username: Username, auth: Auth, logInfo: LogInfo) : Future[Unit] = {
    validateUsername(username)
    accounts.getByName(username, excludeGuests=true).flatMap { result =>
      result match {
        case None => throw new IllegalArgumentException("Unknown username/account.")
        case Some(account) =>
          account.emailVerifyNeeded match {
            case None => throw new Exception("Account email already verified.")
            case Some(x) =>
              if(auth != x)
                throw new Exception("Invalid key, try logging in and having the email verification re-sent.")
              else
                accounts.setEmailVerifyNeeded(username, None).map { result =>
                  logger.info(logInfo + " Verified email for account: " + username)
                } .recover { case exn: Exception =>
                    logger.info(logInfo + " Verify email for account: " + username + " failed with result " + exn)
                }
          }
      }
    }
  }

  def changeEmail(username: Username, password: String, siteAuth: SiteAuth, newEmail: Email, logInfo: LogInfo) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)
      validateEmail(newEmail)
      requiringLogin(siteAuth) { user =>
        accounts.getByName(username, excludeGuests=true).flatMap { result =>
          result match {
            case None => throw new IllegalArgumentException("Unknown username/account.")
            case Some(account) =>
              if(user.name != account.username)
                throw new Exception("Username does not match login.")
              if(newEmail == account.email)
                throw new Exception("New email is the same as the old email.")

              if(!accountStuffBuckets.takeOne(username.toLowerCase, Timestamp.get)) {
                logger.warn(logInfo + " Too many account data changes for " + username)
                throw new Exception("Too many account data changes for account, please wait a few minutes before the next attempt.")
              }

              checkpw(password, account.passwordHash).flatMap { success =>
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
                emailer.sendEmailChangeRequest(newEmail,account.username,auth,account.email).map { case () =>
                  logger.info(logInfo + " Email change initated for account: " + account.username + " from " + account.email + " to " + newEmail)
                }
              }
          }
        }.recover { case exn: Exception =>
            logger.info(logInfo + " Change email for account: " + username + " failed with result " + exn)
            throw exn
        }
      }.get
    }
  }

  def confirmChangeEmail(username: Username, changeAuth: Auth, logInfo: LogInfo) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)
      accounts.getByName(username, excludeGuests=true).flatMap { result =>
        result match {
          case None => throw new IllegalArgumentException("Unknown username/account.")
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
                val oldEmail = account.email
                accounts.setEmail(account.username, newEmail, emailVerifyNeeded = None).map { case () =>
                  //Don't wait for old email to go out
                  emailer.sendOldEmailChangeNotification(oldEmail,account.username,newEmail)
                  logger.info(logInfo + " Email change confirmed for account: " + account.username + " from " + oldEmail + " to " + newEmail)
                }
            }
        }
      }.recover { case exn: Exception =>
          logger.info(logInfo + " Confirm change email for account: " + username + " failed with result " + exn)
          throw exn
      }
    }
  }

  def resendVerifyEmail(username: Username, siteAuth: SiteAuth, logInfo: LogInfo) : Future[Unit] = {
    Future.successful(()).flatMap { case () =>
      validateUsername(username)
      requiringLogin(siteAuth) { user =>
        accounts.getByName(username, excludeGuests=true).map { result =>
          result match {
            case None => throw new IllegalArgumentException("Unknown username/account.")
            case Some(account) =>
              if(user.name != account.username)
                throw new Exception("Username does not match login.")

              if(!accountStuffBuckets.takeOne(username.toLowerCase, Timestamp.get)) {
                logger.warn(logInfo + " Too many account data changes for " + username)
                throw new Exception("Too many account data changes for account, please wait a few minutes before the next attempt.")
              }

              account.emailVerifyNeeded match {
                case None => throw new Exception("Account email already verified.")
                case Some(verifyAuth) =>
                  emailer.sendVerifyEmail(account.email,account.username,verifyAuth)
                  logger.info(logInfo + " User requested resend verify email: " + account.username)
              }
          }
        }
      }.get
    }
  }
}
