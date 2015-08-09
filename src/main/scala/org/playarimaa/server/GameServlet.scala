package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization

import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Accounts.Import._
import org.playarimaa.server.RandGen.Auth
import org.playarimaa.server.RandGen.GameID
import org.playarimaa.server.Utils._

import org.playarimaa.server.game.Games
import org.playarimaa.server.game.GameUtils
import org.playarimaa.server.game.{EndingReason, GameResult, TimeControl}
import org.playarimaa.board.Player
import org.playarimaa.board.{GOLD,SILV}

//TODO: Guard against nans or other weird values when reading floats?

case object GameServlet {

  object IOTypes {
    case class SimpleError(error: String)

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
      endTime: Timestamp
    )

    case class OpenGameData(
      creator: Option[Username],
      joined: List[Username]
    )

    case class ActiveGameData(
      moveStartTime: Timestamp,
      timeSpent: Double,
      gTimeThisMove: Double,
      sTimeThisMove: Double,
      gPresent: Boolean,
      sPresent: Boolean
    )

    case class GameMetadata(
      id: GameID,
      numPly: Int,
      startTime: Option[Timestamp],
      gUser: Username,
      sUser: Username,
      gTC: TimeControl,
      sTC: TimeControl,
      rated: Boolean,
      gameType: String,
      tags: List[String],
      openGameData: Option[OpenGameData],
      activeGameData: Option[ActiveGameData],
      result: Option[GameResult],
      now: Timestamp
    )

    case class MoveTime(
      start: Timestamp,
      time: Timestamp
    )

    case class GameState(
      history: Vector[String],
      moveTimes: Vector[MoveTime],
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
    case class Query(auth: Auth, tc: IOTypes.TimeControl, rated: Boolean, gameType: String)
    case class Reply(gameID: GameID, gameAuth: Auth)
  }
  case object Join extends GameAction {
    val name = "join"
    case class Query(auth: Auth)
    case class Reply(gameAuth: Auth)
  }
  case object Leave extends GameAction {
    val name = "leave"
    case class Query(gameAuth: Auth)
    case class Reply(message: String)
  }
  case object Accept extends GameAction {
    val name = "accept"
    case class Query(gameAuth: Auth, opponent: Username)
    case class Reply(message: String)
  }
  case object Decline extends GameAction {
    val name = "decline"
    case class Query(gameAuth: Auth, opponent: Username)
    case class Reply(message: String)
  }
  case object Heartbeat extends GameAction {
    val name = "heartbeat"
    case class Query(gameAuth: Auth)
    case class Reply(message: String)
  }
  case object Resign extends GameAction {
    val name = "resign"
    case class Query(gameAuth: Auth)
    case class Reply(message: String)
  }
  case object Move extends GameAction {
    val name = "move"
    case class Query(gameAuth: Auth, move: String, plyNum: Int)
    case class Reply(message: String)
  }

  case object GetState {
    case class Query(minSequence: Option[Long], timeout: Option[Int])
    type Reply = IOTypes.GameState

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
  protected implicit lazy val jsonFormats: Formats = DefaultFormats
  protected implicit def executor: ExecutionContext = ec

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
        siteLogin.requiringLogin(query.auth) { username =>
          query.gameType match {
            case "standard" =>
              val tc = TimeControl(
                initialTime = query.tc.initialTime,
                increment = query.tc.increment.getOrElse(0),
                delay = query.tc.delay.getOrElse(0),
                maxReserve = query.tc.maxReserve,
                maxMoveTime = query.tc.maxMoveTime,
                overtimeAfter = query.tc.overtimeAfter
              )
              games.createStandardGame(username, query.auth, tc, query.rated).map { case (gameID,gameAuth) =>
                Create.Reply(gameID,gameAuth)
              }
            case _ =>
              IOTypes.SimpleError("Unsupported game type: " + query.gameType)
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
        siteLogin.requiringLogin(query.auth) { username =>
          games.join(username,query.auth,id).map { gameAuth =>
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

  def convMeta(data: Games.GetData): IOTypes.GameMetadata = {
    IOTypes.GameMetadata(
      id = data.meta.id,
      numPly = data.meta.numPly,
      startTime = data.meta.startTime,
      gUser = data.meta.users(GOLD),
      sUser = data.meta.users(SILV),
      gTC = convTC(data.meta.tcs(GOLD)),
      sTC = convTC(data.meta.tcs(SILV)),
      rated = data.meta.rated,
      gameType = data.meta.gameType,
      tags = data.meta.tags,
      openGameData = data.openGameData.map { ogdata =>
        IOTypes.OpenGameData(
          creator = ogdata.creator,
          joined = ogdata.joined.toList
        )
      },
      activeGameData = data.activeGameData.map { agdata =>
        IOTypes.ActiveGameData(
          moveStartTime = agdata.moveStartTime,
          timeSpent = agdata.timeSpent,
          gTimeThisMove = agdata.timeThisMove(GOLD),
          sTimeThisMove = agdata.timeThisMove(SILV),
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
      val reply = convState(data)
      Json.write(reply)
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
