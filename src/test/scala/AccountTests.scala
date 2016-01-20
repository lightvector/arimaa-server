import org.scalatest._
import org.scalatra.test.scalatest._
import org.scalatest.FunSuiteLike
import akka.actor.{ActorSystem}
import akka.testkit.{TestKit, ImplicitSender}
import org.playarimaa.server.DatabaseConfig.driver.api._
import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext
import com.typesafe.config.ConfigFactory

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server._
import org.playarimaa.server.game._
import org.playarimaa.board.Utils._

import org.playarimaa.server.AccountServlet.IOTypes

class SiteTests(_system: ActorSystem) extends TestKit(_system) with ScalatraFlatSpec with BeforeAndAfterAll {

  def this() = this(ActorSystem("SiteTests"))

  override def afterAll : Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def readJson[T](body: String)(implicit m: Manifest[T]): T = {
    Try(Json.read[T](body)).tagFailure("Error parsing server reply:\n" + body + "\n").get
  }

  ArimaaServerInit.initialize

  val config = ConfigFactory.load
  val siteName = config.getString("siteName")
  val siteAddress = config.getString("siteAddress")
  val domainName = config.getString("domainName")
  val smtpHost = ""//config.getString("smtpHost")
  val smtpPort = 0//config.getInt("smtpPort")
  val smtpTLS = config.getBoolean("smtpTLS")
  val smtpSSL = config.getBoolean("smtpSSL")
  val smtpUser = config.getString("smtpUser")
  val smtpPass = config.getString("smtpPass")
  val smtpDebug = config.getBoolean("smtpDebug")
  val noReplyAddress = config.getString("noReplyAddress")
  val helpAddress = config.getString("helpAddress")

  val actorSystem = system
  val mainEC: ExecutionContext = ExecutionContext.Implicits.global
  val cryptEC: ExecutionContext = mainEC
  val serverInstanceID: Long = System.currentTimeMillis
  val db = DatabaseConfig.getDB()
  val scheduler = actorSystem.scheduler
  val emailer = new Emailer(siteName,siteAddress,smtpHost,smtpPort,smtpTLS,smtpSSL,smtpUser,smtpPass,smtpDebug,noReplyAddress,helpAddress)(mainEC)
  val accounts = new Accounts(domainName,db,scheduler)(mainEC)
  val siteLogin = new SiteLogin(accounts,emailer,cryptEC,scheduler)(mainEC)
  addServlet(new AccountServlet(accounts,siteLogin,mainEC), "/accounts/*")

  val startTime = Timestamp.get

  var carolSiteAuth = ""
  var daveSiteAuth = ""
  var daveSiteAuth2 = ""
  var daveSiteAuth3 = ""
  var ericSiteAuth = ""

  "AccountServer" should "allow users to register accounts" in {

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Carol","carol@domainname.com","password",false,"",None))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Register.Reply](body)
      carolSiteAuth = reply.siteAuth
      (carolSiteAuth.length > 10) should be (true)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(carolSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Carol" && !user.isGuest }) should be (true)
    }

  }

  it should "allow users to log in as guest" in {

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("Dave",None))) {
      status should equal (200)
      val reply = readJson[AccountServlet.LoginGuest.Reply](body)
      daveSiteAuth = reply.siteAuth
      (daveSiteAuth.length > 10) should be (true)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Dave" && user.isGuest }) should be (true)
    }

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("Eric",None))) {
      status should equal (200)
      val reply = readJson[AccountServlet.LoginGuest.Reply](body)
      ericSiteAuth = reply.siteAuth
      (ericSiteAuth.length > 10) should be (true)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(ericSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Eric" && user.isGuest }) should be (true)
    }

  }

  it should "not allow users to register when someone is logged in with that name" in {

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Carol","carol@domainname.com","password",false,"",None))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Dave","dave@domainname.com","password",false,"",None))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }

  }

  it should "not allow guest login when someone is logged in with that name" in {

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("Carol",None))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("dave",None))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }

  }

  it should "allow guest login when providing old auth" in {

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("Dave",Some(daveSiteAuth)))) {
      status should equal (200)
      val reply = readJson[AccountServlet.LoginGuest.Reply](body)
      daveSiteAuth2 = reply.siteAuth
      (daveSiteAuth2.length > 10) should be (true)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (false)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth2))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Dave" && user.isGuest }) should be (true)
    }
  }

  it should "allow register when providing old auth" in {

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Dave","dave@domainname.com","password",false,"",Some(daveSiteAuth)))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Dave","dave@domainname.com","password",false,"",Some(daveSiteAuth2)))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Register.Reply](body)
      daveSiteAuth3 = reply.siteAuth
      (daveSiteAuth3.length > 10) should be (true)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (false)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth2))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (false)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth3))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Dave" && !user.isGuest }) should be (true)
    }

  }


  it should "allow users to log out" in {

    post("/accounts/logout", Json.write(AccountServlet.Logout.Query(carolSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Logout.Reply](body)
    }
    post("/accounts/logout", Json.write(AccountServlet.Logout.Query(daveSiteAuth3))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Logout.Reply](body)
    }
    post("/accounts/logout", Json.write(AccountServlet.Logout.Query(ericSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Logout.Reply](body)
    }

    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(carolSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (false)
    }
    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(daveSiteAuth3))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (false)
    }
    post("/accounts/authLoggedIn", Json.write(AccountServlet.AuthLoggedIn.Query(ericSiteAuth))) {
      status should equal (200)
      val reply = readJson[AccountServlet.AuthLoggedIn.Reply](body)
      (reply.value) should be (false)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.length) should be (0)
    }

  }


  it should "allow guest login after logout only when no nonguest account exists" in {

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("cArol",None))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }
    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("Dave",None))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }

    post("/accounts/loginGuest", Json.write(AccountServlet.LoginGuest.Query("ErIc",None))) {
      status should equal (200)
      val reply = readJson[AccountServlet.LoginGuest.Reply](body)
      ericSiteAuth = reply.siteAuth
      (ericSiteAuth.length > 10) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "ErIc" && user.isGuest }) should be (true)
    }

  }

  it should "not allow regular login with wrong passwords" in {
    post("/accounts/login", Json.write(AccountServlet.Login.Query("Carol","abcdefg"))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }
    post("/accounts/login", Json.write(AccountServlet.Login.Query("Dave","abcdefg"))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }
    post("/accounts/login", Json.write(AccountServlet.Login.Query("Eric",""))) {
      status should equal (200)
      val reply = readJson[IOTypes.SimpleError](body)
    }
  }

  it should "allow regular login with correct passwords" in {
    post("/accounts/login", Json.write(AccountServlet.Login.Query("Carol","password"))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Login.Reply](body)
      carolSiteAuth = reply.siteAuth
      (carolSiteAuth.length > 10) should be (true)
    }
    post("/accounts/login", Json.write(AccountServlet.Login.Query("daVE","password"))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Login.Reply](body)
      daveSiteAuth = reply.siteAuth
      (daveSiteAuth.length > 10) should be (true)
    }

    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Carol" && !user.isGuest }) should be (true)
    }
    post("/accounts/usersLoggedIn", Json.write(AccountServlet.UsersLoggedIn.Query())) {
      status should equal (200)
      val reply = readJson[AccountServlet.UsersLoggedIn.Reply](body)
      (reply.users.exists { user => user.name == "Dave" && !user.isGuest }) should be (true)
    }
  }

}
