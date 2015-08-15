import org.scalatest._
import org.scalatra.test.scalatest._
import org.scalatest.FunSuiteLike
import akka.actor.{ActorSystem}
import akka.testkit.{TestKit, ImplicitSender}
import slick.driver.H2Driver.api._

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server._
import org.playarimaa.server.game._
import org.playarimaa.board.Player
import org.playarimaa.board.{GOLD,SILV}

import org.playarimaa.server.GameServlet.IOTypes

//TODO - note that there is a memory leak when running this test in SBT
//See github issue #49

class GameServletTests(_system: ActorSystem) extends TestKit(_system) with ScalatraFlatSpec with BeforeAndAfterAll {

  def this() = this(ActorSystem("GameServerTests"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  ArimaaServerInit.initialize
  val mainEC = scala.concurrent.ExecutionContext.Implicits.global
  val db = Database.forConfig("h2mem1")
  val accounts = new Accounts(db)(mainEC)
  val siteLogin = new SiteLogin(accounts)(mainEC)
  val scheduler = system.scheduler
  val games = new Games(db,siteLogin.logins,scheduler)(mainEC)
  addServlet(new AccountServlet(siteLogin,mainEC), "/accounts/*")
  addServlet(new GameServlet(siteLogin,games,mainEC), "/games/*")

  val startTime = Timestamp.get
  val tc = IOTypes.TimeControl(
    initialTime = 300,
    increment = Some(10),
    delay = Some(1),
    maxReserve = Some(600),
    maxMoveTime = Some(100),
    overtimeAfter = Some(80)
  )

  var gameID = ""
  var aliceSiteAuth = ""
  var aliceGameAuth = ""
  var bobSiteAuth = ""
  var bobGameAuth = ""
  var bobPlayer: Player = GOLD
  var sequence: Long = -1

  "GameServer" should "allow users to create games" in {

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Bob","bob@domainname.com","password",false))) {
      status should equal (200)
      val reply = Json.read[AccountServlet.Register.Reply](body)
      bobSiteAuth = reply.siteAuth
      (bobSiteAuth.length > 10) should be (true)
    }
    post("/accounts/register", Json.write(AccountServlet.Register.Query("Alice","alice@domainname.com","password",false))) {
      status should equal (200)
      val reply = Json.read[AccountServlet.Register.Reply](body)
      aliceSiteAuth = reply.siteAuth
      (aliceSiteAuth.length > 10) should be (true)
    }

    post("/games/actions/create", Json.write(GameServlet.Create.Query(bobSiteAuth,tc,true,"standard"))) {
      status should equal (200)
      val reply = Json.read[GameServlet.Create.Reply](body)
      gameID = reply.gameID
      bobGameAuth = reply.gameAuth
    }

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = Json.read[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.id should equal (gameID)
      state.meta.numPly should equal (0)
      state.meta.startTime should equal (None)
      state.meta.gUser should equal (None)
      state.meta.sUser should equal (None)
      state.meta.gTC should equal (tc)
      state.meta.sTC should equal (tc)
      state.meta.rated should equal (true)
      state.meta.gameType should equal ("standard")
      state.meta.tags should equal (List())
      state.meta.openGameData.get.creator should equal (Some(IOTypes.ShortUserInfo("Bob")))
      state.meta.openGameData.get.joined should equal (List(IOTypes.ShortUserInfo("Bob")))
      state.meta.activeGameData should equal (None)
      state.meta.result should equal (None)
      state.sequence.exists(_ > sequence) should equal (true)
      sequence = state.sequence.get
    }
  }

  it should "allow users to join games" in {

    post("/games/"+gameID+"/actions/join", Json.write(GameServlet.Join.Query(aliceSiteAuth))) {
      status should equal (200)
      val reply = Json.read[GameServlet.Join.Reply](body)
      aliceGameAuth = reply.gameAuth
    }

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = Json.read[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.id should equal (gameID)
      state.meta.numPly should equal (0)
      state.meta.startTime should equal (None)
      state.meta.gUser should equal (None)
      state.meta.sUser should equal (None)
      state.meta.gTC should equal (tc)
      state.meta.sTC should equal (tc)
      state.meta.rated should equal (true)
      state.meta.gameType should equal ("standard")
      state.meta.tags should equal (List())
      state.meta.openGameData.nonEmpty should equal (true)
      state.meta.openGameData.get.creator should equal (Some(IOTypes.ShortUserInfo("Bob")))
      state.meta.openGameData.get.joined should equal (List(IOTypes.ShortUserInfo("Alice"),IOTypes.ShortUserInfo("Bob")))
      state.meta.activeGameData should equal (None)
      state.meta.result should equal (None)
      state.sequence.exists(_ > sequence) should equal (true)
      sequence = state.sequence.get
    }
  }

  it should "allow users to start games" in {

    post("/games/"+gameID+"/actions/accept", Json.write(GameServlet.Accept.Query(bobGameAuth,"Alice"))) {
      status should equal (200)
      val reply = Json.read[GameServlet.Accept.Reply](body)
    }

    //Wait a little time for the game to start
    Thread.sleep(500)

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = Json.read[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.id should equal (gameID)
      state.meta.numPly should equal (0)
      state.meta.startTime.nonEmpty should equal (true)
      state.meta.gUser.exists(user => user.name == "Alice" || user.name == "Bob") should equal (true)
      state.meta.sUser.exists(user => user.name == "Alice" || user.name == "Bob" && user != state.meta.gUser.get) should equal (true)
      state.meta.gTC should equal (tc)
      state.meta.sTC should equal (tc)
      state.meta.rated should equal (true)
      state.meta.gameType should equal ("standard")
      state.meta.tags should equal (List())
      state.meta.openGameData should equal (None)
      state.meta.activeGameData.nonEmpty should equal (true)
      state.meta.activeGameData.get.gClockBeforeTurn should equal (tc.initialTime)
      state.meta.activeGameData.get.sClockBeforeTurn should equal (tc.initialTime)
      state.meta.activeGameData.get.gPresent should equal (true)
      state.meta.activeGameData.get.sPresent should equal (true)
      state.meta.result should equal (None)
      state.sequence.exists(_ > sequence) should equal (true)
      sequence = state.sequence.get
      bobPlayer = if(state.meta.gUser == Some("Bob")) GOLD else SILV
    }
  }

  it should "allow users to resign games" in {
    post("/games/"+gameID+"/actions/resign", Json.write(GameServlet.Resign.Query(bobGameAuth))) {
      status should equal (200)
      val reply = Json.read[GameServlet.Resign.Reply](body)
    }

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = Json.read[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.id should equal (gameID)
      state.meta.numPly should equal (0)
      state.meta.startTime.nonEmpty should equal (true)
      state.meta.gUser.exists(user => user.name == "Alice" || user.name == "Bob") should equal (true)
      state.meta.sUser.exists(user => user.name == "Alice" || user.name == "Bob" && user != state.meta.gUser.get) should equal (true)
      state.meta.gTC should equal (tc)
      state.meta.sTC should equal (tc)
      state.meta.rated should equal (true)
      state.meta.gameType should equal ("standard")
      state.meta.tags should equal (List())
      state.meta.openGameData should equal (None)
      state.meta.activeGameData should equal (None)
      state.meta.result.nonEmpty should equal (true)
      state.meta.result.get.winner should equal (bobPlayer.flip.toString)
      state.meta.result.get.reason should equal ("r")
      state.sequence should equal (None)
    }
  }
}
