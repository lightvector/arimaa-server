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
import org.playarimaa.board.{Player,GOLD,SILV}
import org.playarimaa.board.Utils._

import org.playarimaa.server.game.GameServlet.IOTypes

class GameServletTests(_system: ActorSystem) extends TestKit(_system) with ScalatraFlatSpec with BeforeAndAfterAll {

  def this() = this(ActorSystem("GameServerTests"))

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
  val smtpHost = ""//config.getString("smtpHost")
  val smtpPort = ""//config.getString("smtpPort")
  val smtpAuth = config.getBoolean("smtpAuth")
  val noReplyAddress = config.getString("noReplyAddress")
  val helpAddress = config.getString("helpAddress")

  val actorSystem = system
  val mainEC: ExecutionContext = ExecutionContext.Implicits.global
  val cryptEC: ExecutionContext = mainEC
  val serverInstanceID: Long = System.currentTimeMillis
  val db = DatabaseConfig.createDB("h2memgame")
  val scheduler = actorSystem.scheduler
  val emailer = new Emailer(siteName,siteAddress,smtpHost,smtpPort,smtpAuth,noReplyAddress,helpAddress)(mainEC)
  val accounts = new Accounts(db,scheduler)(mainEC)
  val siteLogin = new SiteLogin(accounts,emailer,cryptEC,scheduler)(mainEC)
  val games = new Games(db,siteLogin.logins,scheduler,accounts,serverInstanceID)(mainEC)
  addServlet(new AccountServlet(siteLogin,mainEC), "/accounts/*")
  addServlet(new GameServlet(accounts,siteLogin,games,mainEC), "/games/*")

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

  def userInfo(name: String): IOTypes.ShortUserInfo =
    IOTypes.ShortUserInfo(name,Rating.initial.mean,false,false)

  "GameServer" should "allow users to create games" in {

    post("/accounts/register", Json.write(AccountServlet.Register.Query("Bob","bob@domainname.com","password",false))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Register.Reply](body)
      bobSiteAuth = reply.siteAuth
      (bobSiteAuth.length > 10) should be (true)
    }
    post("/accounts/register", Json.write(AccountServlet.Register.Query("Alice","alice@domainname.com","password",false))) {
      status should equal (200)
      val reply = readJson[AccountServlet.Register.Reply](body)
      aliceSiteAuth = reply.siteAuth
      (aliceSiteAuth.length > 10) should be (true)
    }

    post("/games/actions/create", Json.write(GameServlet.Create.StandardQuery(bobSiteAuth,tc,true,None,None,"standard"))) {
      status should equal (200)
      val reply = readJson[GameServlet.Create.Reply](body)
      gameID = reply.gameID
      bobGameAuth = reply.gameAuth
    }

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = readJson[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.gameID should equal (gameID)
      state.meta.numPly should equal (0)
      state.meta.startTime should equal (None)
      state.meta.gUser should equal (None)
      state.meta.sUser should equal (None)
      state.meta.gTC should equal (tc)
      state.meta.sTC should equal (tc)
      state.meta.rated should equal (true)
      state.meta.gameType should equal ("standard")
      state.meta.tags should equal (List())
      state.meta.openGameData.get.creator should equal (Some(userInfo("Bob")))
      state.meta.openGameData.get.joined should equal (List(userInfo("Bob")))
      state.meta.activeGameData should equal (None)
      state.meta.result should equal (None)
      state.meta.sequence.exists(_ > sequence) should equal (true)
      sequence = state.meta.sequence.get
    }
  }

  it should "allow users to join games" in {

    post("/games/"+gameID+"/actions/join", Json.write(GameServlet.Join.Query(aliceSiteAuth))) {
      status should equal (200)
      val reply = readJson[GameServlet.Join.Reply](body)
      aliceGameAuth = reply.gameAuth
    }

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = readJson[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.gameID should equal (gameID)
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
      state.meta.openGameData.get.creator should equal (Some(userInfo("Bob")))
      state.meta.openGameData.get.joined should equal (List(userInfo("Alice"),userInfo("Bob")))
      state.meta.activeGameData should equal (None)
      state.meta.result should equal (None)
      state.meta.sequence.exists(_ > sequence) should equal (true)
      sequence = state.meta.sequence.get
    }
  }

  it should "allow users to start games" in {

    post("/games/"+gameID+"/actions/accept", Json.write(GameServlet.Accept.Query(bobGameAuth,"Alice"))) {
      status should equal (200)
      val reply = readJson[GameServlet.Accept.Reply](body)
    }

    //Wait a little time for the game to start
    Thread.sleep(500)

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = readJson[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.gameID should equal (gameID)
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
      state.meta.sequence.exists(_ > sequence) should equal (true)
      sequence = state.meta.sequence.get
      bobPlayer = if(state.meta.gUser == Some(userInfo("Bob"))) GOLD else SILV
    }
  }

  it should "allow users to resign games" in {
    post("/games/"+gameID+"/actions/resign", Json.write(GameServlet.Resign.Query(bobGameAuth))) {
      status should equal (200)
      val reply = readJson[GameServlet.Resign.Reply](body)
    }

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = readJson[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (0)
      state.moveTimes.length should equal (0)
      state.toMove should equal ("g")
      state.meta.gameID should equal (gameID)
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
      state.meta.sequence should equal (None)
    }
  }

  def beginAliceBobGame(): Unit = {
    post("/games/actions/create", Json.write(GameServlet.Create.StandardQuery(bobSiteAuth,tc,true,Some("alice"),Some("bob"),"standard"))) {
      status should equal (200)
      val reply = readJson[GameServlet.Create.Reply](body)
      gameID = reply.gameID
      bobGameAuth = reply.gameAuth
    }
    post("/games/"+gameID+"/actions/join", Json.write(GameServlet.Join.Query(aliceSiteAuth))) {
      status should equal (200)
      val reply = readJson[GameServlet.Join.Reply](body)
      aliceGameAuth = reply.gameAuth
    }
    post("/games/"+gameID+"/actions/accept", Json.write(GameServlet.Accept.Query(bobGameAuth,"Alice"))) {
      status should equal (200)
      val reply = readJson[GameServlet.Accept.Reply](body)
    }
    //Wait a little time for the game to start
    Thread.sleep(500)
  }
  def sendMove(gameAuth: String, move: String, plyNum: Int): Unit = {
    post("/games/"+gameID+"/actions/move", Json.write(GameServlet.Move.Query(gameAuth,move,plyNum))) {
      status should equal (200)
      val reply = readJson[GameServlet.Move.Reply](body)
    }
  }

  it should "allow users to play a game" in {
    //Fritzlein v Chessandgo 2015 Arimaa WC R12
    beginAliceBobGame()
    sendMove(aliceGameAuth,"Ra1 Rb1 Rc1 Dd1 De1 Rf1 Rg1 Rh1 Ra2 Hb2 Cc2 Ed2 Me2 Cf2 Hg2 Rh2",0)
    sendMove(bobGameAuth,  "ha7 mb7 cc7 dd7 ee7 df7 hg7 rh7 ra8 rb8 rc8 rd8 ce8 rf8 rg8 rh8",1)
    sendMove(aliceGameAuth,"Ed2n Ed3n Ed4n Hg2n",2)
    sendMove(bobGameAuth,  "ee7s hg7s ha7s ce8s",3)
    sendMove(aliceGameAuth,"Hg3n Me2n Hb2n Dd1n",4)
    sendMove(bobGameAuth,  "ee6s rg8s mb7s ra8s",5)
    sendMove(aliceGameAuth,"Hg4e Hh4n Hh5n Me3e",6)
    sendMove(bobGameAuth,  "mb6e mc6e md6e ha6e",7)
    sendMove(aliceGameAuth,"Hh6s rh7s Hh5s rh6s",8)
    sendMove(bobGameAuth,  "ee5e ef5s ef4e Mf3n",9)
    sendMove(aliceGameAuth,"Ed5e Ee5e Mf4s Mf3w",10)
    sendMove(bobGameAuth,  "eg4w ef4w ee4e Me3n",11)
    sendMove(aliceGameAuth,"Ef5w Me4s Hh4s rh5s",12)
    sendMove(bobGameAuth,  "ef4w Me3w ee4s rb8s",13)
    sendMove(aliceGameAuth,"Hh3w rh4s Md3w Hb3n",14)
    sendMove(bobGameAuth,  "hg6s hg5s rh8s rh7s",15)
    sendMove(aliceGameAuth,"Mc3w Hb4w Ee5e Mb3n",16)
    sendMove(bobGameAuth,  "hg4e ee3w ra7s dd7s",17)
    sendMove(aliceGameAuth,"Mb4s Ha4n De1n Rg1n",18)
    sendMove(bobGameAuth,  "ed3e me6e mf6e rh6s",19)
    sendMove(aliceGameAuth,"Mb3n Mb4n Mb5s hb6s",20)
    sendMove(bobGameAuth,  "ee3n ee4w ed4w rb7w",21)
    sendMove(aliceGameAuth,"Ha5s Mb4s hb5s Ra2n",22)
    sendMove(bobGameAuth,  "hb4n hb5n ec4w ra6s",23)
    sendMove(aliceGameAuth,"Ef5e Mb3s Dd2n De2n",24)
    sendMove(bobGameAuth,  "eb4e Ha4e Hb4n ec4w",25)
    sendMove(aliceGameAuth,"Eg5s Eg4n hh4w Ra3n",26)
    sendMove(bobGameAuth,  "Hb5e eb4n Hc5n Hc6x eb5e",27)
    sendMove(aliceGameAuth,"hg4w Eg5s hf4s hf3x Eg4w",28)
    sendMove(bobGameAuth,  "ec5e ed5s Dd3s ed4s",29)
    sendMove(aliceGameAuth,"Mb2n Ef4n Ef5e Rb1n",30)
    sendMove(bobGameAuth,  "De3n ed3e De4e ee3n",31)
    sendMove(aliceGameAuth,"Eg5w Df4e Dg4e Ef5e",32)
    sendMove(bobGameAuth,  "ee4e rd8s ce7s mg6w",33)
    sendMove(aliceGameAuth,"Eg5w Dd2n Ra1n Ra2n",34)
    sendMove(bobGameAuth,  "mf6e mg6s mg5s rg7s",35)
    sendMove(aliceGameAuth,"Rh1w Mb3n Mb4n",36)
    sendMove(bobGameAuth,  "df7s rg6s df6e rf8s",37)
    sendMove(aliceGameAuth,"Mb5e hb6s hb5s Mc5w",38)
    sendMove(bobGameAuth,  "ef4w ee4w ed4w ec4n",39)
    sendMove(aliceGameAuth,"Ef5s Ef4n mg4w Dh4w",40)
    sendMove(bobGameAuth,  "ec5e Mb5e Mc5n Mc6x ed5w",41)
    sendMove(aliceGameAuth,"Rb2n mf4s mf3x Ef5s Ef4w",42)
    sendMove(bobGameAuth,  "hb4n Ra4e hb5n Rb4n",43)
    sendMove(aliceGameAuth,"Hg3w rh3w Hf3w rg3w rf3x",44)
    sendMove(bobGameAuth,  "ec5e ed5e ee5e ef5s",45)
    sendMove(aliceGameAuth,"Rh2n Rh3n Dg4s rg5s",46)
    sendMove(bobGameAuth,  "hb6n Rb5n Rb6e Rc6x hb7s",47)
    sendMove(aliceGameAuth,"Ee4n Rg2e Rh2n Ra3n",48)
    sendMove(bobGameAuth,  "ef4w He3s ee4s ra7s",49)
    sendMove(aliceGameAuth,"Dg3s rg4s rg3w Dg2n",50)
    sendMove(bobGameAuth,  "dg6s dg5s ce6e cf6e",51)
    sendMove(aliceGameAuth,"Ee5e Ef5e Cc2w Rc1n",52)
    sendMove(bobGameAuth,  "dd6e de6s de5e rd7s",53)
    sendMove(aliceGameAuth,"df5w Eg5w He2w Dd3w",54)
    sendMove(bobGameAuth,  "cg6s hb6s cc7s rc8s",55)
    sendMove(aliceGameAuth,"de5w Ef5w dd5s Ee5w",56)
    sendMove(bobGameAuth,  "dg4w ee3w rf3w re3s",57)
    sendMove(aliceGameAuth,"re2n Hd2e re3n He2n",58)
    sendMove(bobGameAuth,  "He3s ed3e dd4w dc4w",59)
    sendMove(aliceGameAuth,"Ed5e Ee5e cg5s Ef5e",60)
    sendMove(bobGameAuth,  "rf7e rg7s df4n re4w",61)
    sendMove(aliceGameAuth,"He2w Hd2n rd4n Hd3n",62)
    sendMove(bobGameAuth,  "db4e hb5s Rb3w hb4s",63)
    sendMove(aliceGameAuth,"cg4w Dg3n Rh3w Hd4s",64)
    sendMove(bobGameAuth,  "rd6e df5n rg6e df6e",65)
    sendMove(aliceGameAuth,"Hd3n dc4n Hd4w Dc3e",66)
    sendMove(bobGameAuth,  "rd5e re5s dc5e cc6w",67)
    sendMove(aliceGameAuth,"Eg5w Ef5w cf4n Dg4w",68)
    sendMove(bobGameAuth,  "dg6s dg5s rh6w ra5e",69)
    sendMove(aliceGameAuth,"cf5n Ee5e Ef5e Rg1n",70)
    sendMove(bobGameAuth,  "cf6s ra6s rb5s dd5e",71)
    sendMove(aliceGameAuth,"re4w Df4w Rg3w Rf3n",72)
    sendMove(bobGameAuth,  "cf5n Rf4n cf6n Rf5n Rf6x",73)
    sendMove(aliceGameAuth,"Hc4n Hc5w cb6e Hb5n",74)
    sendMove(bobGameAuth,  "cc6s cc5w de5w dd5w",75)
    sendMove(aliceGameAuth,"Eg5w Ef5s rd4n De4w",76)
    sendMove(bobGameAuth,  "Dd3w ee3w dc5s re6s",77)
    sendMove(aliceGameAuth,"cb5e Hb6s Ef4w dg4w",78)
    sendMove(bobGameAuth,  "rc7w rb7s re5e df4e",79)
    sendMove(aliceGameAuth,"rd5e Dd4n cc5n Dd5w",80)
    sendMove(bobGameAuth,  "ed3s ed2n Rc2e Dc3x cc6n",81)
    sendMove(aliceGameAuth,"rb6e Hb5n Ee4w Rg2n",82)
    sendMove(bobGameAuth,  "Cb2w hb3s rb4s rg6s",83)
    sendMove(aliceGameAuth,"Ca2s Ca1e Cf2w Rd2w",84)
    sendMove(bobGameAuth,  "Rc2n Rc3x hb2e hc2s rb3s",85)
    sendMove(aliceGameAuth,"Rf1n",86)
    sendMove(bobGameAuth,  "hc1n rb2w ra2s",87)

    get("/games/"+gameID+"/state") {
      status should equal (200)
      val state = readJson[GameServlet.GetState.ReplyIn](body)
      state.history.length should equal (88)
      state.moveTimes.length should equal (88)
      state.toMove should equal ("g")
      state.meta.gameID should equal (gameID)
      state.meta.numPly should equal (88)
      state.meta.startTime.nonEmpty should equal (true)
      state.meta.gUser.exists(user => user.name == "Alice") should equal (true)
      state.meta.sUser.exists(user => user.name == "Bob") should equal (true)
      state.meta.gTC should equal (tc)
      state.meta.sTC should equal (tc)
      state.meta.rated should equal (true)
      state.meta.gameType should equal ("standard")
      state.meta.tags should equal (List())
      state.meta.openGameData should equal (None)
      state.meta.activeGameData should equal (None)
      state.meta.result.nonEmpty should equal (true)
      state.meta.result.get.winner should equal ("s")
      state.meta.result.get.reason should equal ("g")
      state.meta.position should equal ("......../..c..c../.Hr...../r.D.rrrr/R.dE..dR/R..e..R./..h.CR../rC......")
      state.meta.sequence should equal (None)
    }
  }

}
