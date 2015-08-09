import org.scalatest._
import org.scalatra.test.scalatest._
import org.scalatest.FunSuiteLike
import org.playarimaa.server._
import akka.actor.{ActorSystem}
import akka.testkit.{TestKit, ImplicitSender}
import slick.driver.H2Driver.api._

//TODO - note that there is a memory leak when running this test in SBT
//See github issue #49

class ChatServletTests(_system: ActorSystem) extends TestKit(_system) with ScalatraFlatSpec with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  ArimaaServerInit.initialize
  val db = Database.forConfig("h2mem1")
  addServlet(new ChatServlet(system,db,system.dispatcher), "/*")

  val startTime = Timestamp.get
  var bobAuth = ""

  "ChatServer" should "allow users to join and post messages" in {

    get("/main") {
      status should equal (200)
      val reply = Json.read[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (0)
    }

    post("/main/join", Json.write(ChatServlet.Join.Query("Bob"))) {
      status should equal (200)
      val reply = Json.read[ChatServlet.Join.Reply](body)
      bobAuth = reply.auth
      (bobAuth.length > 10) should be (true)
    }

    post("/main/post", Json.write(ChatServlet.Post.Query("Bob",bobAuth,"Hello world"))) {
      status should equal (200)
      val reply = Json.read[ChatServlet.Post.Reply](body)
    }

    post("/main/post", Json.write(ChatServlet.Post.Query("Bob",bobAuth,"I am Bob"))) {
      status should equal (200)
      val reply = Json.read[ChatServlet.Post.Reply](body)
    }

    get("/main") {
      status should equal (200)
      val reply = Json.read[ChatServlet.Get.Reply](body)
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
      val reply = Json.read[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (1)

      lines(0).id should equal (1)
      lines(0).text should equal ("I am Bob")
    }

    get("/main", ("minId" -> "2")) {
      status should equal (200)
      val reply = Json.read[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (0)
    }
  }

  it should "reject posts with invalid auth" in {
    post("/main/post", Json.write(ChatServlet.Post.Query("Bob",bobAuth + "x" ,"Hello world"))) {
      status should equal (200)
      val reply = Json.read[ChatServlet.SimpleError](body)
    }

    get("/main") {
      status should equal (200)
      val reply = Json.read[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (2)
    }
  }

  it should "reject posts after leaving" in {
    post("/main/leave", Json.write(ChatServlet.Leave.Query("Bob",bobAuth))) {
      status should equal (200)
      val reply = Json.read[ChatServlet.Leave.Reply](body)
    }
    post("/main/post", Json.write(ChatServlet.Post.Query("Bob",bobAuth + "x" ,"Hello world"))) {
      status should equal (200)
      val reply = Json.read[ChatServlet.SimpleError](body)
    }
    get("/main") {
      status should equal (200)
      val reply = Json.read[ChatServlet.Get.Reply](body)
      val lines = reply.lines
      lines.length should equal (2)
    }
  }

}
