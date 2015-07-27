package org.playarimaa.server.game
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}
import slick.driver.H2Driver.api._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.RandGen.Auth
import org.playarimaa.server.RandGen.GameID
import org.playarimaa.server.Accounts.Import._
import org.playarimaa.board.Player

class Games(val db: Database)(implicit ec: ExecutionContext) {

//  def createStandardGame(creator: Username, tc: TimeControl, rated: Boolean): Future[GameID] = {
//
//  }
}

// case class OpenGameData(
//   creator: Username,
//   joined: List[Username]
// )

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
  isOpen: Boolean,
  isActive: Boolean,
  result: Option[GameResult]
)

case class MoveInfo(
  move: String,
  time: Double,
  start: Double
)

class MovesTable(tag: Tag) extends Table[MoveInfo](tag, "movesTable") {
  def gameId = column[GameID]("gameId")
  def ply = column[Int]("ply")
  def move = column[String]("move")
  def time = column[Timestamp]("time")
  def start = column[Timestamp]("start")

  def * = (move, time, start) <> (MoveInfo.tupled, MoveInfo.unapply)

  def pk = primaryKey("pk_gameId_ply", (gameId, ply))
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
  def isOpen = column[Boolean]("isOpen")
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
    rated,gameType,tags,isOpen,isActive,
    (winner,reason,endTime)
  ).shaped <> (
    //Database shape -> Scala object
    { case (id,numPly,startTime,gUser,sUser,gTC,sTC,rated,gameType,tags,isOpen,isActive,result) =>
      GameMetadata(id,numPly,startTime,gUser,sUser,
        TimeControl.tupled.apply(gTC),
        TimeControl.tupled.apply(sTC),
        rated,gameType,tags,isOpen,isActive,
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
        g.rated,g.gameType,g.tags,g.isOpen,g.isActive,
        (g.result match {
          case None => (None,None,None)
          case Some(GameResult(winner,reason,endTime)) => (Some(winner),Some(reason),Some(endTime))
        })
      ))
    }
  )
}
