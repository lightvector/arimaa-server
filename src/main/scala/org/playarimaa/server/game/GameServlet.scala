package org.playarimaa.server.game
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, SessionSupport, ScalatraServlet}
import org.scalatra.atmosphere.{AtmosphereSupport,AtmosphereClient}
import org.scalatra.{atmosphere => Atmosphere}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt}
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.{Accounts,Json,LoginTracker,SimpleUserInfo,SiteLogin,Timestamp,WebAppStack}
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._

import org.playarimaa.board.{Player,GOLD,SILV}
import org.playarimaa.board.GameType

object GameServlet {

  object IOTypes {
    case class SimpleError(error: String)

    case class ShortUserInfo(
      name: String,
      rating: Double,
      isBot: Boolean,
      isGuest: Boolean
    )

    case class TimeControl(
      //TODO: should we allow non-integer values for these?
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
      joined: List[ShortUserInfo],
      creationTime: Double
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
      gameID: String,
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
      position: String,
      now: Double,
      sequence: Option[Long]
    )

    case class MoveTime(
      start: Double,
      time: Double
    )

    case class GameState(
      history: Vector[String],
      moveTimes: Vector[MoveTime],
      toMove: String,
      meta: GameMetadata
    )
    //Uses lists instead of vectors to support deserialization.
    //TODO - see https://github.com/json4s/json4s/issues/82
    case class GameStateIn(
      history: List[String],
      moveTimes: List[MoveTime],
      toMove: String,
      meta: GameMetadata
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
    case class StandardQuery(
      siteAuth: String,
      tc: IOTypes.TimeControl,
      rated: Boolean,
      gUser: Option[String],
      sUser: Option[String],
      gameType: String
    )
    case class HandicapQuery(
      siteAuth: String,
      gTC: IOTypes.TimeControl,
      sTC: IOTypes.TimeControl,
      gUser: Option[String],
      sUser: Option[String],
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

  case object GetMetadata {
    case class Query(minSequence: Option[Long], timeout: Option[Int])
    type Reply = IOTypes.GameMetadata

    def parseQuery(params: Map[String,String]): Query = {
      new Query(
        params.get("minSequence").map(_.toLong),
        params.get("timeout").map(_.toInt)
      )
    }
  }

  case object GetSearch {
    case class Query(
      open: Boolean,
      active: Boolean,

      rated: Option[Boolean],
      postal: Option[Boolean],
      gameType: Option[String],

      usersInclude: Option[Set[Username]],
      gUser: Option[Username],
      sUser: Option[Username],
      creator: Option[Username],
      creatorNot: Option[Username],

      minTime: Option[Timestamp],
      maxTime: Option[Timestamp],
      minDateTime: Option[Timestamp],
      maxDateTime: Option[Timestamp],

      limit: Option[Int]
    )
    type Reply = List[IOTypes.GameMetadata]

    def parseQuery(params: Map[String,String]): Query = {
      new Query(
        open = params.get("open").map(_.toBoolean).getOrElse(false),
        active = params.get("active").map(_.toBoolean).getOrElse(false),
        rated = params.get("rated").map(_.toBoolean),
        postal = params.get("postal").map(_.toBoolean),
        gameType = params.get("gameType"),
        usersInclude = (
          if(params.contains("user1") || params.contains("user2"))
            Some(params.get("user1").toSet ++ params.get("user2").toSet)
          else
            None
        ),
        gUser = params.get("gUser"),
        sUser = params.get("sUser"),
        creator = params.get("creator"),
        creatorNot = params.get("creatorNot"),
        minTime = params.get("minTime").map(_.toFiniteDouble),
        maxTime = params.get("maxTime").map(_.toFiniteDouble),
        minDateTime = params.get("minDateTime").map(Timestamp.parse),
        maxDateTime = params.get("maxDateTime").map(Timestamp.parse),
        limit = params.get("limit").map(_.toInt)
      )
    }

  }

}

import org.playarimaa.server.game.GameServlet._

class GameServlet(val accounts: Accounts, val siteLogin: SiteLogin, val games: Games, val ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport with SessionSupport with AtmosphereSupport {
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

  def getUserInfo(username: Username): Future[SimpleUserInfo] = {
    accounts.getByName(username,excludeGuests=false).flatMap {
      case None => Future.failed(new Exception("Unknown user: " + username))
      case Some(acct) => Future(acct.info)
    }
  }

  def maybeGetUser(username: Option[String]): Future[Option[SimpleUserInfo]] = {
    username match {
      case None => Future(None)
      case Some(username) =>
        accounts.getByName(username,excludeGuests=false).flatMap {
          case None => Future.failed(new Exception("Unknown user: " + username))
          case Some(acct) => Future(Some(acct.info))
        }
    }
  }

  def handleGameroomAction(params: Map[String,String], requestBody: String) : AnyRef = {
    getGameroomAction(params("action")) match {
      case None =>
        pass()
      case Some(Create) =>
        val query =
          Try(Json.read[Create.StandardQuery](requestBody)).recoverWith { case _ =>
            Try(Json.read[Create.HandicapQuery](requestBody)).recoverWith { case _ =>
              Failure(new Exception("Unknown game type or invalid fields"))
            }
          }

        query match {
          case Success(Create.StandardQuery(siteAuth,tc,rated,gUser,sUser,"standard")) =>
            siteLogin.requiringLogin(siteAuth) { username =>
              val timeControl = tcOfIOTC(tc)
              maybeGetUser(gUser).flatMap { gUser =>
                maybeGetUser(sUser).flatMap { sUser =>
                  games.createStandardGame(username, siteAuth, timeControl, rated, gUser, sUser).map { case (gameID,gameAuth) =>
                    Create.Reply(gameID,gameAuth)
                  }
                }
              }
            }.get
          case Success(Create.HandicapQuery(siteAuth,gTC,sTC,gUser,sUser,"handicap")) =>
            siteLogin.requiringLogin(siteAuth) { username =>
              val gTimeControl = tcOfIOTC(gTC)
              val sTimeControl = tcOfIOTC(sTC)
              maybeGetUser(gUser).flatMap { gUser =>
                maybeGetUser(sUser).flatMap { sUser =>
                  games.createHandicapGame(username, siteAuth, gTimeControl, sTimeControl, gUser, sUser).map { case (gameID,gameAuth) =>
                    Create.Reply(gameID,gameAuth)
                  }
                }
              }
            }.get
          case Success(_) =>
            IOTypes.SimpleError("Bug: unknown game create success type")
          case Failure(err) =>
            IOTypes.SimpleError(err.getMessage)
        }
    }
  }

  def handleGameAction(id: GameID, params: Map[String,String], requestBody: String) : AnyRef = {
    getGameAction(params("action")) match {
      case None =>
        pass()
      case Some(Join) =>
        val query = Json.read[Join.Query](requestBody)
        siteLogin.requiringLogin(query.siteAuth) { username =>
          games.join(username,query.siteAuth,id).map { gameAuth =>
            Join.Reply(gameAuth)
          }
        }.get.get
      case Some(Leave) =>
        val query = Json.read[Leave.Query](requestBody)
        games.leave(id,query.gameAuth).map { case () =>
          Leave.Reply("Ok")
        }.get
      case Some(Accept) =>
        val query = Json.read[Accept.Query](requestBody)
        getUserInfo(query.opponent).map { opponent =>
          games.accept(id,query.gameAuth,opponent).map { case () =>
            Accept.Reply("Ok")
          }.get
        }
      case Some(Decline) =>
        val query = Json.read[Decline.Query](requestBody)
        games.decline(id,query.gameAuth,query.opponent).map { case () =>
          Decline.Reply("Ok")
        }.get
      case Some(Heartbeat) =>
        val query = Json.read[Heartbeat.Query](requestBody)
        games.heartbeat(id,query.gameAuth).map { case () =>
          Heartbeat.Reply("Ok")
        }.get
      case Some(Resign) =>
        val query = Json.read[Resign.Query](requestBody)
        games.resign(id,query.gameAuth).map { case () =>
          Resign.Reply("Ok")
        }.get
      case Some(Move) =>
        val query = Json.read[Move.Query](requestBody)
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

  def convUser(user: SimpleUserInfo): IOTypes.ShortUserInfo = {
    IOTypes.ShortUserInfo(user.name,user.rating.mean,user.isBot,user.isGuest)
  }

  def convMeta(data: Games.GetMetadata): IOTypes.GameMetadata = {
    IOTypes.GameMetadata(
      gameID = data.meta.id,
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
          joined = ogdata.joined.toList.sorted.map(convUser(_)),
          creationTime = ogdata.creationTime
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
      position = data.meta.position,
      now = Timestamp.get,
      sequence = data.sequence
    )
  }

  def convState(data: Games.GetData): IOTypes.GameState = {
    IOTypes.GameState(
      history = data.moves.map(_.move),
      moveTimes = data.moves.map { info => IOTypes.MoveTime(start = info.start, time = info.time) },
      toMove = GameUtils.nextPlayer(data.moves.length).toString,
      meta = convMeta(data.meta)
    )
  }

  def handleGetState(id: GameID, params: Map[String,String]) : AnyRef = {
    val query = GetState.parseQuery(params)
    val timeout = math.min(Games.GET_MAX_TIMEOUT, query.timeout.map(_.toDouble).getOrElse(Games.GET_DEFAULT_TIMEOUT))
    games.get(id, query.minSequence, timeout).map { data =>
      convState(data)
    }
  }

  def handleGetMetadata(id: GameID, params: Map[String,String]) : AnyRef = {
    val query = GetMetadata.parseQuery(params)
    val timeout = math.min(Games.GET_MAX_TIMEOUT, query.timeout.map(_.toDouble).getOrElse(Games.GET_DEFAULT_TIMEOUT))
    games.getMetadata(id, query.minSequence, timeout).map { data =>
      convMeta(data)
    }
  }

  def handleGetSearch(params: Map[String,String]) : AnyRef = {
    val query = GetSearch.parseQuery(params)
    val searchParams = Games.SearchParams(
      open = query.open,
      active = query.active,
      rated = query.rated,
      postal = query.postal,
      gameType = query.gameType,
      usersInclude = query.usersInclude,
      gUser = query.gUser,
      sUser = query.sUser,
      creator = query.creator,
      creatorNot = query.creatorNot,
      minTime = (query.minTime ++ query.minDateTime).reduceOption[Double](math.max),
      maxTime = (query.maxTime ++ query.maxDateTime).reduceOption[Double](math.min),
      limit = query.limit
    )
    games.searchMetadata(searchParams).map { data =>
      data.map(convMeta(_))
    }
  }


  post("/actions/:action") {
    handleGameroomAction(params,request.body)
  }

  post("/:gameID/actions/:action") {
    val id = params("gameID")
    handleGameAction(id,params,request.body)
  }

  get("/:gameID/state") {
    val id = params("gameID")
    handleGetState(id,params)
  }
  get("/:gameID/metadata") {
    val id = params("gameID")
    handleGetMetadata(id,params)
  }
  get("/search") {
    handleGetSearch(params)
  }

  //TODO doesn't work yet due to scalatra atmosphere bug
  atmosphere("/:gameID/stateStream") {
    val id = params("gameID")
    var connected = false
    new AtmosphereClient {
      def receive = {
        case Atmosphere.Connected =>
          var lastSequence = Games.INITIAL_SEQUENCE - 1
          def loop(): Unit = {
            if(connected) {
              games.get(id, Some(lastSequence+1), Games.GET_MAX_TIMEOUT).onComplete { result =>
                //If the connection is still active after getting the game...
                if(connected) {
                  result match {
                    case Failure(e) =>
                      send(Json.write(IOTypes.SimpleError(e.getMessage())))
                    case Success(data) =>
                      //Send back the gamestate if it's new
                      if(!data.meta.sequence.exists(_ <= lastSequence))
                        send(Json.write(convState(data)))
                      //If the game is active or open (gamestate has a sequence number), then repeat
                      data.meta.sequence.foreach { sequence =>
                        lastSequence = sequence
                        assert(data.meta.openGameData.nonEmpty || data.meta.activeGameData.nonEmpty)
                        loop
                      }
                  }
                }
              }
            }
          }
          connected = true
          loop()
        case Atmosphere.Disconnected(disconnector, None) =>
          connected = false
          ()
        case Atmosphere.Disconnected(disconnector, Some(error)) =>
          connected = false
          logger.error("gameID " + id + ": " + disconnector + " disconnected due to error " + error)
        case Atmosphere.Error(None) =>
          logger.error("gameID " + id + ": unknown error event")
        case Atmosphere.Error(Some(error)) =>
          logger.error("gameID " + id + ": " + error)
        case Atmosphere.TextMessage(_) =>
          ()
        case Atmosphere.JsonMessage(_) =>
          ()
      }
    }
  }

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
