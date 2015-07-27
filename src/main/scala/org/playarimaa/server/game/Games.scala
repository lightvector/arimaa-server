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

object Games {
  val HEARTBEAT_TIMEOUT = 15.0
  val STANDARD_TYPE = "standard"

  val gameTable = TableQuery[GameTable]
  val movesTable = TableQuery[MovesTable]

}

class Timed[T](val value: T) {
  var lastTime = Timestamp.get
  def heartbeat =
    lastTime = Timestamp.get
  def youngerThan(age: Double, now: Timestamp): Boolean =
    now - lastTime < age
}
object Timed {
  def apply[T](x:T) =
    new Timed(x)
}

class Games(val db: Database)(implicit ec: ExecutionContext) {

  case class OpenGameData(
    val creator: Timed[Username],
    var joined: List[Timed[Username]],
    val meta: GameMetadata,

    //A flag indicating that this game has been started and will imminently be removed
    var starting: Boolean
  )

  //Lock protects both the mapping itself and all of the mutable contents.
  private val openGamesLock = new Object
  private var openGames: Map[GameID,OpenGameData] = Map()


  def createStandardGame(creator: Username, tc: TimeControl, rated: Boolean): Future[GameID] = {
    assignGameIdAndAddOpenGame { id: GameID =>
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
        isActive = false,
        result = None
      )
      val openGameData = OpenGameData(
        creator = Timed(creator),
        joined = List(),
        meta = meta,
        starting = false
      )
      openGameData
    }
  }

  /* Generates a GameID that doesn't collide with anything currently in the database
   * or any open games and atomically inserts the specified record into the openGames map. */
  private def assignGameIdAndAddOpenGame(f: (GameID => OpenGameData)): Future[GameID] = {
    val id = RandGen.genGameID

    //Check for collisions
    val query: Rep[Int] = Games.gameTable.filter(_.id === id).length
    db.run(query.result).flatMap { count =>
      var shouldRetry = false
      openGamesLock.synchronized {
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
          data.joined = data.joined.filter(_.youngerThan(Games.HEARTBEAT_TIMEOUT,now))
          //Any open games whose creators haven't heartbeated recently can be removed.
          data.creator.youngerThan(Games.HEARTBEAT_TIMEOUT,now)
        }
      }
    }
    //TODO active games
  }

  // private def beginGame(id: GameID, meta: GameMetadata, gUser: Username, sUser: Username): Future[Unit] = {
  //   //Write metadata to DB
  //   val query = Games.gameTable += meta
  //   //TODO oncomplete does not map a future!
  //   db.run(DBIO.seq(query)).onComplete { result =>
  //     //Whether the db insertion worked or not, clear out the open game.
  //     openGamesLock.synchronized {
  //       openGames = openGames - id
  //     }
  //     //Fail if the db query failed
  //     (result.get : Unit)

  //     //Add an active game, loading any existing moves.
  //     loadMovesFromDB(id).map {

  //   //Return id of new game
  //   //   meta.id
  //   // }
  //   }
  // }

  private def loadMovesFromDB(id: GameID): Future[List[MoveInfo]] = {
    val query = Games.movesTable.filter(_.gameID === id).sortBy(_.ply)
    db.run(query.result).map(_.toList)
  }
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
  isActive: Boolean,
  result: Option[GameResult]
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
  def isActive = column[Boolean]("isActive")

  def winner = column[Option[Player]]("winner")
  def reason = column[Option[EndingReason]]("reason")
  def endTime = column[Option[Timestamp]]("endTime")

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
    rated,gameType,tags,isActive,
    (winner,reason,endTime)
  ).shaped <> (
    //Database shape -> Scala object
    { case (id,numPly,startTime,gUser,sUser,gTC,sTC,rated,gameType,tags,isActive,result) =>
      GameMetadata(id,numPly,startTime,gUser,sUser,
        TimeControl.tupled.apply(gTC),
        TimeControl.tupled.apply(sTC),
        rated,gameType,tags,isActive,
        result match {
          case (None, None, None) => None
          case (Some(winner), Some(reason), Some(endTime)) => Some(GameResult.tupled.apply(winner,reason,endTime))
          case _ => throw new Exception("Not all of (winner,reason,endTime) defined when parsing db row: "+ result)
        }
      )
    },
    //Scala object -> Database shape
    { g: GameMetadata =>
      Some((
        g.id,g.numPly,g.startTime,g.gUser,g.sUser,
        TimeControl.unapply(g.gTC).get,
        TimeControl.unapply(g.sTC).get,
        g.rated,g.gameType,g.tags,g.isActive,
        (g.result match {
          case None => (None,None,None)
          case Some(GameResult(winner,reason,endTime)) => (Some(winner),Some(reason),Some(endTime))
        })
      ))
    }
  )
}
