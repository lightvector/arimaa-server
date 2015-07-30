package org.playarimaa.server.game
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}
import slick.driver.H2Driver.api._
import org.playarimaa.server.Timestamp
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.RandGen
import org.playarimaa.server.RandGen.Auth
import org.playarimaa.server.RandGen.GameID
import org.playarimaa.server.Accounts.Import._
import org.playarimaa.board.Player
import org.playarimaa.server.Utils._

object Games {
  val HEARTBEAT_TIMEOUT = 15.0
  val STANDARD_TYPE = "standard"

  val gameTable = TableQuery[GameTable]
  val movesTable = TableQuery[MovesTable]

}

case class HeartbeatTime(var lastTime: Timestamp) {
  def heartbeat =
    lastTime = Timestamp.get
  def youngerThan(age: Double, now: Timestamp): Boolean =
    now - lastTime < age
}



class Games(val db: Database)(implicit ec: ExecutionContext) {

  //TODO document game lifecycle

  case class OpenGameData(
    val meta: GameMetadata,
    val creator: Username,
    val creatorHeartbeat: HeartbeatTime,
    var joined: Map[Username,HeartbeatTime],

    //A flag indicating that this game has been started and will imminently be removed
    var starting: Boolean
  )

  //Lock protects both the mapping itself and all of the mutable contents.
  private val openGamesLock = new Object()
  //All open games are listed in this map, and NOT in the database, unless the game
  //is currently in the process of being started and therefore entered into the database.
  private var openGames: Map[GameID,OpenGameData] = Map()

  case class ActiveGameData(
    var meta: GameMetadata,
    var moves: List[MoveInfo],
    var moveStartTime: Timestamp,
    var gTimeThisMove: Double,
    var sTimeThisMove: Double,
    var gPresent: Boolean,
    var sPresent: Boolean,
    var gHeartbeat: HeartbeatTime,
    var sHeartbeat: HeartbeatTime
  )

  //Lock protects both the mapping itself and all of the mutable contents.
  private val activeGamesLock = new Object()
  //All active games are in this map, and also any game in this map has an entry in the database
  private var activeGames: Map[GameID,ActiveGameData] = Map()

  def createStandardGame(creator: Username, tc: TimeControl, rated: Boolean): Future[GameID] = {
    assignGameIdAndAddOpenGame { id: GameID =>
      val now = Timestamp.get
      val meta = GameMetadata(
        id = id,
        numPly = 0,
        startTime = None,
        gUser = None,
        sUser = None,
        gTC = tc,
        sTC = tc,
        rated = rated,
        gameType = Games.STANDARD_TYPE,
        tags = List(),
        result = GameResult(
          player = None,
          reason = EndingReason.ADJOURNED,
          endTime = now
        )
      )
      val openGameData = OpenGameData(
        meta = meta,
        creator = creator,
        creatorHeartbeat = HeartbeatTime(now),
        joined = List(),
        starting = false
      )
      openGameData
    }
  }

  /* Generates a GameID that doesn't collide with anything currently in the database
   * or any open games and inserts the specified open game record into the [openGames] map. */
  private def assignGameIdAndAddOpenGame(f: (GameID => OpenGameData)): Future[GameID] = {
    val id = RandGen.genGameID

    //Check for collisions by querying the table to see if we have a colliding id
    val query: Rep[Int] = Games.gameTable.filter(_.id === id).length
    db.run(query.result).flatMap { count =>
      var shouldRetry = false
      openGamesLock.synchronized {
        //We have a collision if the db contains a match, or if we collide against an open game
        shouldRetry = (count != 0) || openGames.contains(id)
        if(!shouldRetry)
          openGames = openGames + (id -> f(id))
      }
      if(shouldRetry)
        assignGameIdAndAddOpenGame(f)
      else
        Future(id)
    }
  }

  /* Applies the effect of heartbeat timeouts to all open and active games */
  private def applyTimeouts: Unit = {
    //Open games
    openGamesLock.synchronized {
      val now = Timestamp.get
      openGames = openGames.filter { case (id,data) =>
        //If this game is starting right now with the current players, then don't time anyone out
        //or otherwise clean up the open game
        if(!data.starting)
          true
        else {
          //Any joined players who haven't sent recent heartbeats should be un-joined.
          data.joined = data.joined.filter { case (_,heartbeat) =>
            heartbeat.youngerThan(Games.HEARTBEAT_TIMEOUT,now)
          }
          //Any open games whose creators haven't heartbeated recently can be removed.
          data.creatorHeartbeat.youngerThan(Games.HEARTBEAT_TIMEOUT,now)
        }
      }
    }

    //Active games
    activeGamesLock.synchronized {
      val now = Timestamp.get
      activeGames.foreach { case (_,data) =>
        //If a player has not heartbeated recently, that player is no longer present in the game.
        if(data.gPresent.value && !data.gPresent.youngerThan(Games.HEARTBEAT_TIMEOUT,now))
          data.gPresent.value = false
        if(data.sPresent.value && !data.sPresent.youngerThan(Games.HEARTBEAT_TIMEOUT,now))
          data.sPresent.value = false
      }
    }
  }

  private def tryOpenAndActive[T](id: GameID)(f: OpenGameData => Try[T])(g: ActiveGameData => Try[T]): Try[T] = {
    //First try to find an open game with this id
    None.orElse {
      openGamesLock.synchronized {
        openGames.get(id).map(f)
      }
    //Otherwise, if we found no such open game, try to find an active game with this id
    }.orElse {
      activeGamesLock.synchronized {
        activeGames.get(id).map(g)
      }
    //And collect the results
    } match {
      case None => Failure(new Exception("No open or active game with the given id."))
      case Some(x) => x
    }
  }

  /* Attempt to join an open or active game with the specified id */
  def join(user: Username, id: GameID): Try[Unit] = {
    tryOpenAndActive(id) { (data: OpenGameData) =>
      if(data.creator == user || data.joined.contains(user))
        Failure(new Exception("Already joined the open game with this id."))
      else {
        data.joined = data.joined + (user -> HeartbeatTime(Timestamp.get))
        Success(())
      }
    } { (data: ActiveGameData) =>
      if(data.meta.gUser.get == user) {
        if(data.gPresent)
          Failure(new Exception("Already joined the active game with this id."))
        else {
          data.gPresent = true
          data.gHeartbeat.heartbeat
          Success(())
        }
      }
      else if(data.meta.sUser.get == user) {
        if(data.sPresent)
          Failure(new Exception("Already joined the active game with this id."))
        else {
          data.sPresent = true
          data.sHeartbeat.heartbeat
          Success(())
        }
      }
      else
        Failure(new Exception("Not one of the players of this game."))
    }
  }

  //TODO redo this in terms of GameAuths that get heartbeated instead?

  /* Attempt to heartbeat an open or active game with the specified id */
  def heartbeat(user: User, id: GameID): Try[Unit] = {
    tryOpenAndActive(id) { (data: OpenGameData) =>
      if(data.creator == user) {
        data.creatorHeartbeat.heartbeat
        Success(())
      }
      else {
        data.joined.get(user) match {
          case None => Failure(new Exception("Not joined or timed out with the open game with this id."))
          case Some(heartbeat) =>
            heartbeat.heartbeat
            Success(())
        }
      }
    } { (data: ActiveGameData) =>
      if(data.meta.gUser.get == user) {
        if(!data.gPresent)
          Failure(new Exception("Not joined or timed out with the active game with this id."))
        else {
          data.gHeartbeat.heartbeat
          Success(())
        }
      }
      else if(data.meta.sUser.get == user) {
        if(!data.sPresent)
          Failure(new Exception("Not joined or timed out with the active game with this id."))
        else {
          data.sHeartbeat.heartbeat
          Success(())
        }
      }
      else
        Failure(new Exception("Not one of the players of this game."))
    }
  }

  def leave(user: Username, id: GameID) = {

  }



  //TODO accept function
  //TODO reject function

  /* Actually begin an open game and convert it into an active game */
  private def beginGame(meta: GameMetadata, gUser: Username, sUser: Username): Future[Unit] = {
    //Write metadata to DB
    val id = meta.id

    //Update metadata with new information
    var now = Timestamp.get
    var newMeta = meta.copy(
      startTime = meta.startTime.orElse(Some(now)),
      gUser = gUser,
      sUser = sUser,
      result = meta.result.copy(
        endTime = now
      )
    )

    val query = Games.gameTable += newMeta
    db.run(DBIO.seq(query)).resultMap { result =>
      //Whether the db insertion worked or not, clear out the open game.
      openGamesLock.synchronized {
        assert(openGames.get(id).exists(_.starting))
        openGames = openGames - id
      }
      //Fail if the db query failed
      (result.get : Unit)

      //Add an active game, loading any existing moves.
      loadMovesFromDB(id).map { moves =>
        //TODO check if newMeta.numPly is the same as moves.length?
        now = Timestamp.get
        newMeta = newMeta.copy(
          numPly = moves.length,
          result = newMeta.result.copy(
            endTime = now
          )
        )

        val activeGameData = ActiveGameData(
          meta = newMeta,
          moves = moves,
          moveStartTime = now,
          gTimeThisMove = computeTimeLeft(newMeta.gTC,moves,GOLD),
          sTimeThisMove = computeTimeLeft(newMeta.sTC,moves,SILV),
          gPresent = Timed(true,now),
          sPresent = Timed(true,now)
        )
        activeGames = activeGames + (id -> activeGameData)
      }
    }
  }

  /* Compute the amount of time on a player clock given the move history of the game */
  private def computeTimeLeft(tc: Timecontrol, moves: List[MoveInfo], player: Player): Double = {
    val timeUsageHistory =
      moves.zipWithIndex
        .filter{ case (x,i) => (i % 2 == 0) == (player == GOLD) }
        .map(_._1)
    tc.timeLeftFromHistory(timeLeftFromHistory)
  }

  /* Load all existing moves for a game from the database */
  private def loadMovesFromDB(id: GameID): Future[List[MoveInfo]] = {
    val query = Games.movesTable.filter(_.gameID === id).sortBy(_.ply)
    db.run(query.result).map(_.toList)
  }

  /* The next player to play, based on the number of half-moves played */
  private def nextPlayer(numPly: Int): Player = {
    if(numPly % 2 == 0)
      GOLD
    else
      SILV
  }

  /* The game result entered into the database for any unfinished game so that in case the
   * server goes down, any game active at that time appears adjourned without a winner. */
  private def adjournedResult: Gameresult =
    GameResult(
      player = None,
      reason = EndingReason.ADJOURNED,
      endTime = Timestamp.get
    )

}


// case class ActiveGameData(
//   moveStartTime: Timestamp,
//   gTimeThisMove: Double,
//   sTimeThisMove: Double,
//   gPresent: Boolean,
//   sPresent: Boolean
// )

case class GameMetadata(
  id: GameID,
  numPly: Int,
  startTime: Option[Timestamp],
  gUser: Option[Username],
  sUser: Option[Username],
  gTC: TimeControl,
  sTC: TimeControl,
  rated: Boolean,
  gameType: String,
  tags: List[String],
  result: GameResult
)

case class MoveInfo(
  gameID: GameID,
  ply: Int,
  move: String,
  time: Double,
  start: Double
)

class MovesTable(tag: Tag) extends Table[MoveInfo](tag, "movesTable") {
  def gameID = column[GameID]("gameID")
  def ply = column[Int]("ply")
  def move = column[String]("move")
  def time = column[Timestamp]("time")
  def start = column[Timestamp]("start")

  def * = (gameID, ply, move, time, start) <> (MoveInfo.tupled, MoveInfo.unapply)

  def pk = primaryKey("pk_gameID_ply", (gameID, ply))
}

class GameTable(tag: Tag) extends Table[GameMetadata](tag, "gameTable") {
  def id = column[GameID]("id", O.PrimaryKey)
  def numPly = column[Int]("numPly")
  def startTime = column[Option[Timestamp]]("startTime")
  def gUser = column[Option[Username]]("gUser")
  def sUser = column[Option[Username]]("sUser")

  def gInitialTime = column[Int]("gInitialTime")
  def gIncrement = column[Int]("gIncrement")
  def gDelay = column[Int]("gDelay")
  def gMaxReserve = column[Int]("gMaxReserve")
  def gMaxMoveTime = column[Int]("gMaxMoveTime")
  def gOvertimeAfter = column[Int]("gOvertimeAfter")

  def sInitialTime = column[Int]("sInitialTime")
  def sIncrement = column[Int]("sIncrement")
  def sDelay = column[Int]("sDelay")
  def sMaxReserve = column[Int]("sMaxReserve")
  def sMaxMoveTime = column[Int]("sMaxMoveTime")
  def sOvertimeAfter = column[Int]("sOvertimeAfter")

  def rated = column[Boolean]("rated")
  def gameType = column[String]("gameType")
  def tags = column[List[String]]("tags")

  def winner = column[Option[Player]]("winner")
  def reason = column[EndingReason]("reason")
  def endTime = column[Timestamp]("endTime")

  implicit val listStringMapper = MappedColumnType.base[List[String], String] (
    { list => list.mkString(",") },
    { str => str.split(",").toList }
  )
  implicit val playerMapper = MappedColumnType.base[Player, String] (
    { player => player.toString },
    { str => Player.ofString(str).get }
  )
  implicit val endingReasonMapper = MappedColumnType.base[EndingReason, String] (
    { reason => reason.toString },
    { str => EndingReason.ofString(str).get }
  )

  def * = (
    //Define database projection shape
    id,numPly,startTime,gUser,sUser,
    (gInitialTime,gIncrement,gDelay,gMaxReserve,gMaxMoveTime,gOvertimeAfter),
    (sInitialTime,sIncrement,sDelay,sMaxReserve,sMaxMoveTime,sOvertimeAfter),
    rated,gameType,tags,
    (winner,reason,endTime)
  ).shaped <> (
    //Database shape -> Scala object
    { case (id,numPly,startTime,gUser,sUser,gTC,sTC,rated,gameType,tags,result) =>
      GameMetadata(id,numPly,startTime,gUser,sUser,
        TimeControl.tupled.apply(gTC),
        TimeControl.tupled.apply(sTC),
        rated,gameType,tags,
        GameResult.tupled.apply(result)
      )
    },
    //Scala object -> Database shape
    { g: GameMetadata =>
      Some((
        g.id,g.numPly,g.startTime,g.gUser,g.sUser,
        TimeControl.unapply(g.gTC).get,
        TimeControl.unapply(g.sTC).get,
        g.rated,g.gameType,g.tags,
        GameResult.unapply(g.result).get
      ))
    }
  )
}
