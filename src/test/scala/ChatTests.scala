import org.scalatest._
import org.scalatra.test.scalatest._
import org.scalatest.FunSuiteLike
import akka.actor.{ActorSystem}
import akka.testkit.{TestKit, ImplicitSender}
import slick.driver.H2Driver.api._
import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server._
import org.playarimaa.server.chat._
import org.playarimaa.board.Utils._

class ChatServletTests(_system: ActorSystem) extends TestKit(_system) with ScalatraFlatSpec with BeforeAndAfterAll {

  def this() = this(ActorSystem("ChatTests"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def readJson[T](body: String)(implicit m: Manifest[T]): T = {
    Try(Json.read[T](body)).tagFailure("Error parsing server reply:\n" + body + "\n").get
  }

  ArimaaServerInit.initialize
  val actorSystem = system
  val mainEC: ExecutionContext = ExecutionContext.Implicits.global
  val actorEC: ExecutionContext = actorSystem.dispatcher
  val db = ArimaaServerInit.createDB("h2memchat")
  val accounts = new Accounts(db)(mainEC)
  val siteLogin = new SiteLogin(accounts)(mainEC)
  val scheduler = actorSystem.scheduler
  val chat = new ChatSystem(db,siteLogin.logins,actorSystem)(actorEC)
  addServlet(new AccountServlet(siteLogin,mainEC), "/accounts/*")
  addServlet(new ChatServlet(accounts,siteLogin,chat,actorEC), "/*")

  val startTime = Timestamp.get
  var bobSiteAuth = ""
  var bobChatAuth = ""

  "ChatServer" should "allow users to join and post messages" in {

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Bob","bob@domainname.com","password",false))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Register.Reply](body)
      bobSiteAuth = reply.siteAuth
      (bobSiteAuth.length > 10) should be (true)
    }

    get("/main") {
      status should equal (200)
      val reply = readJson[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (0)
    }

    post("/main/join", Json.write(ChatServlet.Join.Query(bobSiteAuth))) {
      status should equal (200)
      val reply = readJson[ChatServlet.Join.Reply](body)
      bobChatAuth = reply.chatAuth
      (bobChatAuth.length > 10) should be (true)
    }

    post("/main/post", Json.write(ChatServlet.Post.Query(bobChatAuth,"Hello world"))) {
      status should equal (200)
      val reply = readJson[ChatServlet.Post.Reply](body)
    }

    post("/main/post", Json.write(ChatServlet.Post.Query(bobChatAuth,"I am Bob"))) {
      status should equal (200)
      val reply = readJson[ChatServlet.Post.Reply](body)
    }

    get("/main") {
      status should equal (200)
      val reply = readJson[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (2)

      lines(0).id should equal (0)
      lines(0).username should equal ("Bob")
      lines(0).channel should equal ("main")
      lines(0).text should equal ("Hello world")
      lines(0).timestamp >= startTime should be (true)
      lines(0).timestamp <= Timestamp.get should be (true)

      lines(1).id should equal (1)
      lines(1).username should equal ("Bob")
      lines(1).channel should equal ("main")
      lines(1).text should equal ("I am Bob")
      lines(1).timestamp >= lines(0).timestamp should be (true)
      lines(1).timestamp <= Timestamp.get should be (true)
    }
  }

  it should "not return lines earlier than minId" in {
    get("/main", ("minId" -> "1")) {
      status should equal (200)
      val reply = readJson[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (1)

      lines(0).id should equal (1)
      lines(0).text should equal ("I am Bob")
    }

    get("/main", ("minId" -> "2")) {
      status should equal (200)
      val reply = readJson[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (0)
    }
  }

  it should "reject posts with invalid auth" in {
    post("/main/post", Json.write(ChatServlet.Post.Query(bobChatAuth + "x" ,"Hello world"))) {
      status should equal (200)
      val reply = readJson[ChatServlet.IOTypes.SimpleError](body)
    }

    get("/main") {
      status should equal (200)
      val reply = readJson[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (2)
    }
  }

  it should "reject posts after leaving" in {
    post("/main/leave", Json.write(ChatServlet.Leave.Query(bobChatAuth))) {
      status should equal (200)
      val reply = readJson[ChatServlet.Leave.Reply](body)
    }
    post("/main/post", Json.write(ChatServlet.Post.Query(bobChatAuth + "x" ,"Hello world"))) {
      status should equal (200)
      val reply = readJson[ChatServlet.IOTypes.SimpleError](body)
    }
    get("/main") {
      status should equal (200)
      val reply = readJson[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (2)
    }
  }

}
