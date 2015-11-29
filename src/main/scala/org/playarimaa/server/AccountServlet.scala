package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt}
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._

object AccountServlet {

  object IOTypes {
    case class SimpleError(error: String)
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
      ResetPassword
    )
  }

  case object Register extends Action {
    val name = "register"
    case class Query(username: String, email: String, password: String, isBot: Boolean)
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
    case class Reply(users: List[String])
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
}

import org.playarimaa.server.AccountServlet._

class AccountServlet(val siteLogin: SiteLogin, val ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = Json.formats
  protected implicit def executor: ExecutionContext = ec

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  def getAction(action: String): Option[Action] = {
    Action.all.find(_.name == action)
  }

  def handleAction(params: Map[String,String]) : AnyRef = {
    getAction(params("action")) match {
      case None =>
        pass()
      case Some(Register) =>
        val query = Json.read[Register.Query](request.body)
        siteLogin.register(query.username, query.email, query.password, query.isBot).map { case (username,siteAuth) =>
          Json.write(Register.Reply(username, siteAuth))
        }
      case Some(Login) =>
        val query = Json.read[Login.Query](request.body)
        siteLogin.login(query.username, query.password).map { case (username,siteAuth) =>
          Json.write(Login.Reply(username, siteAuth))
        }
      case Some(LoginGuest) =>
        val query = Json.read[LoginGuest.Query](request.body)
        siteLogin.loginGuest(query.username).map { case (username,siteAuth) =>
          Json.write(LoginGuest.Reply(username, siteAuth))
        }
      case Some(Logout) =>
        val query = Json.read[Logout.Query](request.body)
        siteLogin.logout(query.siteAuth).map { case () =>
          Json.write(Logout.Reply("Ok"))
        }.get
      case Some(AuthLoggedIn) =>
        val query = Json.read[AuthLoggedIn.Query](request.body)
        val isLoggedIn = siteLogin.isAuthLoggedIn(query.siteAuth)
        Json.write(AuthLoggedIn.Reply(isLoggedIn))
      case Some(UsersLoggedIn) =>
        val usersLoggedIn = siteLogin.usersLoggedIn.toList
        Json.write(UsersLoggedIn.Reply(usersLoggedIn))
      case Some(ForgotPassword) =>
        val query = Json.read[ForgotPassword.Query](request.body)
        siteLogin.forgotPassword(query.username): Unit
        Json.write(ForgotPassword.Reply("An email with further instructions was sent to the address associated with this account."))
      case Some(ResetPassword) =>
        val query = Json.read[ResetPassword.Query](request.body)
        siteLogin.resetPassword(query.username, query.resetAuth, query.password).map { case () =>
          Json.write(ResetPassword.Reply("New password set."))
        }
    }
  }

  post("/:action") {
    handleAction(params)
  }

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
