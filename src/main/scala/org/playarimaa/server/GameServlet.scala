package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._

import org.playarimaa.server.game.Games
import org.playarimaa.server.game.GameUtils
import org.playarimaa.server.game.{EndingReason, GameResult, TimeControl}
import org.playarimaa.board.{Player,GOLD,SILV}
import org.playarimaa.board.GameType

//TODO: Guard against nans or other weird values when reading floats?

object GameServlet {

  object IOTypes {
    case class SimpleError(error: String)

    case class ShortUserInfo(
      name: String
    )

    case class TimeControl(
      initialTime: Int,
      increment: Option[Int],
      delay: Option[Int],
      maxReserve: Option[Int],
      maxMoveTime: Option[Int],
      overtimeAfter: Option[Int]
    )

    case class GameResult(
      winner: String,
      reason: String,
      endTime: Double
    )

    case class OpenGameData(
      creator: Option[ShortUserInfo],
      joined: List[ShortUserInfo]
    )

    case class ActiveGameData(
      moveStartTime: Double,
      timeSpent: Double,
      gClockBeforeTurn: Double,
      sClockBeforeTurn: Double,
      gPresent: Boolean,
      sPresent: Boolean
    )

    case class GameMetadata(
      id: String,
      numPly: Int,
      startTime: Option[Double],
      gUser: Option[ShortUserInfo],
      sUser: Option[ShortUserInfo],
      gTC: TimeControl,
      sTC: TimeControl,
      rated: Boolean,
      gameType: String,
      tags: List[String],
      openGameData: Option[OpenGameData],
      activeGameData: Option[ActiveGameData],
      result: Option[GameResult],
      now: Double
    )

    case class MoveTime(
      start: Double,
      time: Double
    )

    case class GameState(
      history: Vector[String],
      moveTimes: Vector[MoveTime],
      toMove: String,
      meta: GameMetadata,
      sequence: Option[Long]
    )
    //Uses lists instead of vectors to support deserialization.
    //TODO - see https://github.com/json4s/json4s/issues/82
    case class GameStateIn(
      history: List[String],
      moveTimes: List[MoveTime],
      toMove: String,
      meta: GameMetadata,
      sequence: Option[Long]
    )
  }

  trait Action {
    val name: String
    abstract class Query
    abstract class Reply
  }
  sealed trait GameroomAction extends Action
  object GameroomAction {
    val all: List[GameroomAction] = List(
      Create
    )
  }
  sealed trait GameAction extends Action
  object GameAction {
    val all: List[GameAction] = List(
      Join,
      Leave,
      Accept,
      Decline,
      Heartbeat,
      Resign,
      Move
    )
  }

  case object Create extends GameroomAction {
    val name = "create"
    case class Query(
      siteAuth: String,
      tc: Option[IOTypes.TimeControl],
      gTC: Option[IOTypes.TimeControl],
      sTC: Option[IOTypes.TimeControl],
      rated: Option[Boolean],
      gameType: String
    )
    case class Reply(gameID: String, gameAuth: String)
  }
  case object Join extends GameAction {
    val name = "join"
    case class Query(siteAuth: String)
    case class Reply(gameAuth: String)
  }
  case object Leave extends GameAction {
    val name = "leave"
    case class Query(gameAuth: String)
    case class Reply(message: String)
  }
  case object Accept extends GameAction {
    val name = "accept"
    case class Query(gameAuth: String, opponent: String)
    case class Reply(message: String)
  }
  case object Decline extends GameAction {
    val name = "decline"
    case class Query(gameAuth: String, opponent: String)
    case class Reply(message: String)
  }
  case object Heartbeat extends GameAction {
    val name = "heartbeat"
    case class Query(gameAuth: String)
    case class Reply(message: String)
  }
  case object Resign extends GameAction {
    val name = "resign"
    case class Query(gameAuth: String)
    case class Reply(message: String)
  }
  case object Move extends GameAction {
    val name = "move"
    case class Query(gameAuth: String, move: String, plyNum: Int)
    case class Reply(message: String)
  }

  case object GetState {
    case class Query(minSequence: Option[Long], timeout: Option[Int])
    type Reply = IOTypes.GameState
    type ReplyIn = IOTypes.GameStateIn

    def parseQuery(params: Map[String,String]): Query = {
      new Query(
        params.get("minSequence").map(_.toLong),
        params.get("timeout").map(_.toInt)
      )
    }
  }

}

import org.playarimaa.server.GameServlet._

class GameServlet(val siteLogin: SiteLogin, val games: Games, val ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = Json.formats
  protected implicit def executor: ExecutionContext = ec

  val logger =  LoggerFactory.getLogger(getClass)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  def getGameroomAction(action: String): Option[GameroomAction] = {
    GameroomAction.all.find(_.name == action)
  }
  def getGameAction(action: String): Option[GameAction] = {
    GameAction.all.find(_.name == action)
  }

  def handleGameroomAction(params: Map[String,String]) = {
    getGameroomAction(params("action")) match {
      case None =>
        pass()
      case Some(Create) =>
        val query = Json.read[Create.Query](request.body)
        siteLogin.requiringLogin(query.siteAuth) { username =>
          (GameType.ofString(query.gameType), query) match {
            case (Success(GameType.STANDARD), Create.Query(siteAuth,Some(tc),None,None,Some(rated),_)) =>
              val timeControl = tcOfIOTC(tc)
              games.createStandardGame(username, siteAuth, timeControl, rated).map { case (gameID,gameAuth) =>
                Create.Reply(gameID,gameAuth)
              }
            case (Success(GameType.HANDICAP), Create.Query(siteAuth,None,Some(gTC),Some(sTC),None,_)) =>
              val gTimeControl = tcOfIOTC(gTC)
              val sTimeControl = tcOfIOTC(sTC)
              games.createHandicapGame(username, siteAuth, gTimeControl, sTimeControl).map { case (gameID,gameAuth) =>
                Create.Reply(gameID,gameAuth)
              }
            case (Success(GameType.DIRECTSETUP), _) =>
              IOTypes.SimpleError("GameType directsetup not yet implemented")
            case (Success(gt),_) =>
              IOTypes.SimpleError("Invalid fields provided for GameType " + gt)
            case (Failure(err),_) =>
              IOTypes.SimpleError(err.getMessage)
          }
        }.get
    }
  }

  def handleGameAction(id: GameID, params: Map[String,String]) = {
    getGameAction(params("action")) match {
      case None =>
        pass()
      case Some(Join) =>
        val query = Json.read[Join.Query](request.body)
        siteLogin.requiringLogin(query.siteAuth) { username =>
          games.join(username,query.siteAuth,id).map { gameAuth =>
            Join.Reply(gameAuth)
          }
        }.get.get
      case Some(Leave) =>
        val query = Json.read[Leave.Query](request.body)
        games.leave(id,query.gameAuth).map { case () =>
          Leave.Reply("Ok")
        }.get
      case Some(Accept) =>
        val query = Json.read[Accept.Query](request.body)
        games.accept(id,query.gameAuth,query.opponent).map { case () =>
          Accept.Reply("Ok")
        }.get
      case Some(Decline) =>
        val query = Json.read[Decline.Query](request.body)
        games.decline(id,query.gameAuth,query.opponent).map { case () =>
          Decline.Reply("Ok")
        }.get
      case Some(Heartbeat) =>
        val query = Json.read[Heartbeat.Query](request.body)
        games.heartbeat(id,query.gameAuth).map { case () =>
          Heartbeat.Reply("Ok")
        }.get
      case Some(Resign) =>
        val query = Json.read[Resign.Query](request.body)
        games.resign(id,query.gameAuth).map { case () =>
          Resign.Reply("Ok")
        }.get
      case Some(Move) =>
        val query = Json.read[Move.Query](request.body)
        games.move(id,query.gameAuth,query.move,query.plyNum).map { case () =>
          Move.Reply("Ok")
        }.get
    }
  }

  def tcOfIOTC(tc: IOTypes.TimeControl): TimeControl = {
    TimeControl(
      initialTime = tc.initialTime,
      increment = tc.increment.getOrElse(0),
      delay = tc.delay.getOrElse(0),
      maxReserve = tc.maxReserve,
      maxMoveTime = tc.maxMoveTime,
      overtimeAfter = tc.overtimeAfter
    )
  }

  def convTC(tc: TimeControl): IOTypes.TimeControl = {
    IOTypes.TimeControl(
      initialTime = tc.initialTime,
      increment = Some(tc.increment),
      delay = Some(tc.delay),
      maxReserve = tc.maxReserve,
          maxMoveTime = tc.maxMoveTime,
      overtimeAfter = tc.overtimeAfter
    )
  }

  def convUser(username: Username): IOTypes.ShortUserInfo = {
    IOTypes.ShortUserInfo(username)
  }

  def convMeta(data: Games.GetData): IOTypes.GameMetadata = {
    IOTypes.GameMetadata(
      id = data.meta.id,
      numPly = data.meta.numPly,
      startTime = data.meta.startTime,
      gUser = data.openGameData.map(_.users(GOLD)).getOrElse(Some(data.meta.users(GOLD))).map(convUser(_)),
      sUser = data.openGameData.map(_.users(SILV)).getOrElse(Some(data.meta.users(SILV))).map(convUser(_)),
      gTC = convTC(data.meta.tcs(GOLD)),
      sTC = convTC(data.meta.tcs(SILV)),
      rated = data.meta.rated,
      gameType = data.meta.gameType.toString,
      tags = data.meta.tags,
      openGameData = data.openGameData.map { ogdata =>
        IOTypes.OpenGameData(
          creator = ogdata.creator.map(convUser(_)),
          joined = ogdata.joined.toList.sorted.map(convUser(_))
        )
      },
      activeGameData = data.activeGameData.map { agdata =>
        IOTypes.ActiveGameData(
          moveStartTime = agdata.moveStartTime,
          timeSpent = agdata.timeSpent,
          gClockBeforeTurn = agdata.clockBeforeTurn(GOLD),
          sClockBeforeTurn = agdata.clockBeforeTurn(SILV),
          gPresent = agdata.present(GOLD),
          sPresent = agdata.present(SILV)
        )
      },
      result = {
        if(data.openGameData.nonEmpty || data.activeGameData.nonEmpty)
          None
        else Some(IOTypes.GameResult(
          winner = data.meta.result.winner.map(_.toString).getOrElse("n"),
          reason = data.meta.result.reason.toString,
          endTime = data.meta.result.endTime
        ))
      },
      now = Timestamp.get
    )
  }

  def convState(data: Games.GetData): IOTypes.GameState = {
    IOTypes.GameState(
      history = data.moves.map(_.move),
      moveTimes = data.moves.map { info => IOTypes.MoveTime(start = info.start, time = info.time) },
      toMove = GameUtils.nextPlayer(data.moves.length).toString,
      meta = convMeta(data),
      sequence = data.sequence
    )
  }

  def handleGetState(id: GameID, params: Map[String,String]) = {
    val query = GetState.parseQuery(params)
    games.get(id, query.minSequence, query.timeout.map(_.toDouble)).map { data =>
      convState(data)
    }
  }


  post("/actions/:action") {
    handleGameroomAction(params)
  }

  post("/:gameID/actions/:action") {
    val id = params("gameID")
    handleGameAction(id,params)
  }

  get("/:gameID/state") {
    val id = params("gameID")
    handleGetState(id,params)
  }

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
