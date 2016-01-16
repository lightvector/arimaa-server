package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt}
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._

object AccountServlet {

  object Constants {
    //10 registrations from an IP address, refilling at a rate of 1 per 5 minutes
    val REGISTER_BUCKET_CAPACITY: Double = 10.0
    val REGISTER_BUCKET_FILL_PER_SEC: Double = 1.0 / 60.0 / 5.0
    val REGISTER_LIMIT_MESSAGE: String = "Too many registrations from this IP in a short time period, wait a few minutes before trying again."

    //Per-IP throttle on all actions together in this servlet: 40 initial, refilling at a rate of 2 per second
    val REQUEST_BUCKET_CAPACITY: Double = 40.0
    val REQUEST_BUCKET_FILL_PER_SEC: Double = 2.0
    val REQUEST_LIMIT_MESSAGE: String = "Too many requests from this IP in a short time period."
  }

  object IOTypes {
    case class SimpleError(error: String)

    case class ShortUserInfo(
      name: String,
      rating: Double,
      ratingStdev: Double,
      isBot: Boolean,
      isGuest: Boolean
    )
  }

  sealed trait Action {
    val name: String
    abstract class Query
    abstract class Reply
  }
  object Action {
    val all: List[Action] = List(
      Register,
      Login,
      LoginGuest,
      Logout,
      AuthLoggedIn,
      UsersLoggedIn,
      ForgotPassword,
      ResetPassword,
      ChangePassword,
      ChangeEmail,
      ConfirmChangeEmail,
      ResendVerifyEmail,
      VerifyEmail
    )
  }

  case object Register extends Action {
    val name = "register"
    case class Query(username: String, email: String, password: String, isBot: Boolean, priorRating: String)
    case class Reply(username: String, siteAuth: String)
  }
  case object Login extends Action {
    val name = "login"
    case class Query(username: String, password: String)
    case class Reply(username: String, siteAuth: String)
  }
  case object LoginGuest extends Action {
    val name = "loginGuest"
    case class Query(username: String)
    case class Reply(username: String, siteAuth: String)
  }
  case object Logout extends Action {
    val name = "logout"
    case class Query(siteAuth: String)
    case class Reply(message: String)
  }
  case object AuthLoggedIn extends Action {
    val name = "authLoggedIn"
    case class Query(siteAuth: String)
    case class Reply(value: Boolean)
  }
  case object UsersLoggedIn extends Action {
    val name = "usersLoggedIn"
    case class Query()
    case class Reply(users: List[IOTypes.ShortUserInfo])
  }
  case object ForgotPassword extends Action {
    val name = "forgotPassword"
    case class Query(username: String)
    case class Reply(message: String)
  }
  case object ResetPassword extends Action {
    val name = "resetPassword"
    case class Query(username: String, resetAuth: String, password: String)
    case class Reply(message: String)
  }
  case object ChangePassword extends Action {
    val name = "changePassword"
    case class Query(username: String, password: String, siteAuth: String, newPassword: String)
    case class Reply(message: String)
  }
  case object ChangeEmail extends Action {
    val name = "changeEmail"
    case class Query(username: String, password: String, siteAuth: String, newEmail: String)
    case class Reply(message: String)
  }
  case object ConfirmChangeEmail extends Action {
    val name = "confirmChangeEmail"
    case class Query(username: String, changeAuth: String)
    case class Reply(message: String)
  }
  case object ResendVerifyEmail extends Action {
    val name = "resendVerifyEmail"
    case class Query(username: String, siteAuth: String)
    case class Reply(message: String)
  }
  case object VerifyEmail extends Action {
    val name = "verifyEmail"
    case class Query(username: String, verifyAuth: String)
    case class Reply(message: String)
  }

  case object GetNotifications {
    case class Query()
    type Reply = List[String]
  }
}

import org.playarimaa.server.AccountServlet._
import org.playarimaa.server.AccountServlet.Constants._

class AccountServlet(val accounts: Accounts, val siteLogin: SiteLogin, val ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = Json.formats
  protected implicit def executor: ExecutionContext = ec

  val logger =  LoggerFactory.getLogger(getClass)

  //Buckets throttling by remote host/ip address
  val requestBuckets: TimeBuckets[String] = new TimeBuckets(REQUEST_BUCKET_CAPACITY, REQUEST_BUCKET_FILL_PER_SEC)
  val registerBuckets: TimeBuckets[String] = new TimeBuckets(REGISTER_BUCKET_CAPACITY, REGISTER_BUCKET_FILL_PER_SEC)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  def getAction(action: String): Option[Action] = {
    Action.all.find(_.name == action)
  }

  def convUser(user: SimpleUserInfo): IOTypes.ShortUserInfo = {
    IOTypes.ShortUserInfo(user.name,user.rating.mean,user.rating.stdev,user.isBot,user.isGuest)
  }

  def handleAction(params: Map[String,String], remoteHost: String, logInfo: LogInfo) : AnyRef = {
    getAction(params("action")) match {
      case None =>
        pass()
      case Some(Register) =>
        val now = Timestamp.get
        if(!registerBuckets.takeOne(remoteHost,now)) {
          logger.warn(logInfo + " " + REGISTER_LIMIT_MESSAGE)
          throw new Exception(REGISTER_LIMIT_MESSAGE)
        }
        val query = Json.read[Register.Query](request.body)
        val priorRating = query.priorRating.trim match {
          case "" => None
          case s => Some(s.toFiniteDouble)
        }
        siteLogin.register(query.username, query.email, query.password, query.isBot, priorRating, logInfo).map { case (username,siteAuth) =>
          Json.write(Register.Reply(username, siteAuth))
        }
      case Some(Login) =>
        val query = Json.read[Login.Query](request.body)
        siteLogin.login(query.username, query.password, logInfo).map { case (username,siteAuth) =>
          Json.write(Login.Reply(username, siteAuth))
        }
      case Some(LoginGuest) =>
        val query = Json.read[LoginGuest.Query](request.body)
        siteLogin.loginGuest(query.username, logInfo).map { case (username,siteAuth) =>
          Json.write(LoginGuest.Reply(username, siteAuth))
        }
      case Some(Logout) =>
        val query = Json.read[Logout.Query](request.body)
        siteLogin.logout(query.siteAuth, logInfo).map { case () =>
          Json.write(Logout.Reply("Ok"))
        }.get
      case Some(AuthLoggedIn) =>
        val query = Json.read[AuthLoggedIn.Query](request.body)
        val isLoggedIn = siteLogin.isAuthLoggedInAndHeartbeat(query.siteAuth)
        Json.write(AuthLoggedIn.Reply(isLoggedIn))
      case Some(UsersLoggedIn) =>
        val usersLoggedIn = siteLogin.usersLoggedIn.toList.map(convUser(_))
        Json.write(UsersLoggedIn.Reply(usersLoggedIn))
      case Some(ForgotPassword) =>
        val query = Json.read[ForgotPassword.Query](request.body)
        siteLogin.forgotPassword(query.username, logInfo).map { case () =>
          Json.write(ForgotPassword.Reply("An email with further instructions was sent to the address associated with this account."))
        }
      case Some(ResetPassword) =>
        val query = Json.read[ResetPassword.Query](request.body)
        siteLogin.resetPassword(query.username, query.resetAuth, query.password, logInfo).map { case () =>
          Json.write(ResetPassword.Reply("New password set."))
        }
      case Some(ChangePassword) =>
        val query = Json.read[ChangePassword.Query](request.body)
        siteLogin.changePassword(query.username, query.password, query.siteAuth, query.newPassword, logInfo).map { case () =>
          Json.write(ChangePassword.Reply("New password set."))
        }
      case Some(ChangeEmail) =>
        val query = Json.read[ChangeEmail.Query](request.body)
        siteLogin.changeEmail(query.username, query.password, query.siteAuth, query.newEmail, logInfo).map { case () =>
          Json.write(ChangeEmail.Reply("Email sent to new address, please confirm from there to complete this change."))
        }
      case Some(ConfirmChangeEmail) =>
        val query = Json.read[ConfirmChangeEmail.Query](request.body)
        siteLogin.confirmChangeEmail(query.username, query.changeAuth, logInfo).map { case () =>
          Json.write(ConfirmChangeEmail.Reply("New email set."))
        }
      case Some(ResendVerifyEmail) =>
        val query = Json.read[ResendVerifyEmail.Query](request.body)
        siteLogin.resendVerifyEmail(query.username, query.siteAuth, logInfo).map { case () =>
          Json.write(ResendVerifyEmail.Reply("Ok."))
        }
      case Some(VerifyEmail) =>
        val query = Json.read[VerifyEmail.Query](request.body)
        siteLogin.verifyEmail(query.username, query.verifyAuth, logInfo).map { case () =>
          Json.write(VerifyEmail.Reply("Account registration complete."))
        }
    }
  }

  def handleGetNotifications(username: Username, siteAuth: SiteAuth, params: Map[String,String], logInfo: LogInfo) : AnyRef = {
    siteLogin.requiringLogin(siteAuth) { user: SimpleUserInfo =>
      if(user.name != username)
        throw new Exception(SiteLogin.Constants.NO_LOGIN_MESSAGE)
      accounts.getNotifications(username)
    }.get
  }

  post("/:action") {
    val remoteHost: String = request.getRemoteHost
    val logInfo = LogInfo(remoteHost=remoteHost)
    val now = Timestamp.get
    if(!requestBuckets.takeOne(remoteHost,now)) {
      logger.warn(logInfo + " " + REQUEST_LIMIT_MESSAGE)
      throw new Exception(REQUEST_LIMIT_MESSAGE)
    }
    handleAction(params,remoteHost,logInfo)
  }

  get("/:username/:siteAuth/notifications") {
    val username: String = params("username")
    val siteAuth: String = params("siteAuth")
    val remoteHost: String = request.getRemoteHost
    val logInfo = LogInfo(remoteHost=remoteHost)
    val now = Timestamp.get
    if(!requestBuckets.takeOne(remoteHost,now)) {
      logger.warn(logInfo + " " + REQUEST_LIMIT_MESSAGE)
      throw new Exception(REQUEST_LIMIT_MESSAGE)
    }
    handleGetNotifications(username,siteAuth,params,logInfo)
  }

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
