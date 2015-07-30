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
import org.playarimaa.server.LoginTracker
import org.playarimaa.server.Utils._
import org.playarimaa.board.Player
import org.playarimaa.board.{GOLD,SILV}
import org.playarimaa.board.{Game,Notation,StandardNotation}

object Games {
  //Time out users from games if they don't heartbeat at least this often
  val INACTIVITY_TIMEOUT = 15.0
  //Clean up a game with no creator after this many seconds if there is nobody in it
  val NO_CREATOR_GAME_CLEANUP_AGE = 600.0
  val STANDARD_TYPE = "standard"

  val gameTable = TableQuery[GameTable]
  val movesTable = TableQuery[MovesTable]

}

class Games(val db: Database)(implicit ec: ExecutionContext) {

  //TODO document game lifecycle

  case class OpenGameData(
    val meta: GameMetadata,
    val creator: Option[Username],
    val creationTime: Timestamp,
    val logins: LoginTracker,
    //A map indicating who has accepted to play who
    var accepted: Map[Username,Username],
    //A flag indicating that this game has been started and will imminently be removed
    var starting: Boolean
  )

  //Lock protects both the mapping itself and all of the mutable contents.
  private val openGamesLock = new Object()
  //All open games are listed in this map, and NOT in the database, unless the game
  //is currently in the process of being started and therefore entered into the database.
  private var openGames: Map[GameID,OpenGameData] = Map()

  case class ActiveGameData(
    val logins: LoginTracker,
    var meta: GameMetadata,
    var moves: List[MoveInfo],
    var moveStartTime: Timestamp,
    var gTimeThisMove: Double,
    var sTimeThisMove: Double,
    var game: Game
  )

  //Lock protects both the mapping itself and all of the mutable contents.
  private val activeGamesLock = new Object()
  //All active games are in this map, and also any game in this map has an entry in the database
  private var activeGames: Map[GameID,ActiveGameData] = Map()

  def createStandardGame(creator: Username, tc: TimeControl, rated: Boolean): Future[(GameID,Auth)] = {
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
          winner = None,
          reason = EndingReason.ADJOURNED,
          endTime = now
        )
      )
      val game = OpenGameData(
        meta = meta,
        creator = Some(creator),
        creationTime = now,
        logins = new LoginTracker(Games.INACTIVITY_TIMEOUT),
        accepted = Map(),
        starting = false
      )
      val gameAuth = game.logins.login(creator,now)
      (game, gameAuth)
    }
  }

  /* Generates a GameID that doesn't collide with anything currently in the database
   * or any open games and inserts the specified open game record into the [openGames] map. */
  private def assignGameIdAndAddOpenGame(f: (GameID => (OpenGameData,Auth))): Future[(GameID,Auth)] = {
    val id = RandGen.genGameID

    //Check for collisions by querying the table to see if we have a colliding id
    val query: Rep[Int] = Games.gameTable.filter(_.id === id).length
    db.run(query.result).flatMap { count =>
      var shouldRetry = false
      var ret: Option[(GameID,Auth)] = None
      openGamesLock.synchronized {
        //We have a collision if the db contains a match, or if we collide against an open game
        shouldRetry = (count != 0) || openGames.contains(id)
        if(!shouldRetry) {
          val (game,gameAuth) = f(id)
          openGames = openGames + (id -> game)
          ret = Some((id,gameAuth))
        }
      }
      if(shouldRetry)
        assignGameIdAndAddOpenGame(f)
      else
        Future(ret.get)
    }
  }

  //TODO call this in places
  /* Applies the effect of heartbeat timeouts to all open and active games */
  private def applyTimeouts: Unit = {
    //Open games
    openGamesLock.synchronized {
      val now = Timestamp.get
      openGames = openGames.filter { case (id,game) =>
        //TODO report changes on timeout?
        game.logins.doTimeouts(now)
        //If this game is starting right now with the current players, then don't clean up the game
        if(game.starting)
          true
        else {
          game.creator match {
            //No creator - keep the game if anyone is joined or the game is young
            case None =>
              game.logins.isAnyoneLoggedIn ||
              now <= game.logins.lastActiveTime + Games.NO_CREATOR_GAME_CLEANUP_AGE
            //A user created this game - keep it if the user is joined
            case Some(creator) =>
              game.logins.isUserLoggedIn(creator)
          }
        }
      }
    }

    //Active games
    activeGamesLock.synchronized {
      val now = Timestamp.get
      activeGames.foreach { case (_,game) =>
        //TODO report changes on timeout?
        game.logins.doTimeouts(now)
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
  def join(user: Username, id: GameID): Try[Auth] = {
    tryOpenAndActive(id) { (game: OpenGameData) =>
      val now = Timestamp.get
      game.logins.doTimeouts(now)
      Success(game.logins.login(user,now))
    } { (game: ActiveGameData) =>
      val now = Timestamp.get
      game.logins.doTimeouts(now)
      if(game.meta.gUser.get != user && game.meta.sUser.get != user)
        Failure(new Exception("Not one of the players of this game."))
      else
        Success(game.logins.login(user,now))
    }
  }

  /* Attempt to heartbeat an open or active game with the specified id */
  def heartbeat(user: Username, id: GameID, gameAuth: Auth): Try[Unit] = {
    tryOpenAndActive(id) { (game: OpenGameData) =>
      val now = Timestamp.get
      game.logins.doTimeouts(now)
      if(game.logins.heartbeat(user,gameAuth,now))
        Success(())
      else
        Failure(new Exception("Not joined or timed out with the open game with this id."))
    } { (game: ActiveGameData) =>
      val now = Timestamp.get
      game.logins.doTimeouts(now)
      if(game.meta.gUser.get != user && game.meta.sUser.get != user)
        Failure(new Exception("Not one of the players of this game."))
      else if(game.logins.heartbeat(user,gameAuth,now))
        Success(())
      else
        Failure(new Exception("Not joined or timed out with the active game with this id."))
    }
  }

  def leave(user: Username, id: GameID, gameAuth: Auth): Try[Unit] = {
    tryOpenAndActive(id) { (game: OpenGameData) =>
      val now = Timestamp.get
      game.logins.doTimeouts(now)
      if(game.logins.heartbeat(user,gameAuth,now)) {
        game.logins.logout(user,gameAuth,now)
        Success(())
      }
      else
        Failure(new Exception("Not joined or timed out with the open game with this id."))
    } { (game: ActiveGameData) =>
      val now = Timestamp.get
      game.logins.doTimeouts(now)
      if(game.meta.gUser.get != user && game.meta.sUser.get != user)
        Failure(new Exception("Not one of the players of this game."))
      else if(game.logins.heartbeat(user,gameAuth,now)) {
        game.logins.logout(user,gameAuth,now)
        Success(())
      }
      else
        Failure(new Exception("Not joined or timed out with the active game with this id."))
    }
  }

  def accept(user: Username, id: GameID, gameAuth: Auth, opponent: Username): Try[Unit] = {
    openGamesLock.synchronized {
      openGames.get(id) match {
        case None => Failure(new Exception("No open game with the given id."))
        case Some(game) =>
          //TODO factor out doing timeouts and make it also remove any accepts with timed out users
          //And same for logouts
          val now = Timestamp.get
          game.logins.doTimeouts(now)
          if(!game.logins.heartbeat(user,gameAuth,now))
            Failure(new Exception("Not joined or timed out with the open game with this id."))
          else {
            game.accepted = game.accepted + (user -> opponent)
            val shouldBegin = game.creator match {
              case None => game.accepted.get(user) == Some(opponent) && game.accepted.get(opponent) == Some(user)
              case Some(creator) => user == creator
            }
            if(shouldBegin) {
              def otherUser(u: Username) = if(u == user) opponent else user
              game.starting = true
              val (gUser,sUser) = (game.meta.gUser, game.meta.sUser) match {
                case (Some(gUser), Some(sUser)) => (gUser,sUser)
                case (None, Some(sUser)) => (otherUser(sUser),sUser)
                case (Some(gUser), None) => (gUser,otherUser(gUser))
                case (None, None) =>
                  if(RandGen.genBoolean)
                    (user,opponent)
                  else
                    (opponent,user)
              }
              //TODO don't ignore?
              //Ignore return value - just schedule the game to begin
              beginGame(game.meta,game.logins,gUser,sUser)
            }
            Success(())
          }
      }
    }
  }

  def reject(user: Username, id: GameID, gameAuth: Auth, opponent: Username): Try[Unit] = {
    openGamesLock.synchronized {
      openGames.get(id) match {
        case None => Failure(new Exception("No open game with the given id."))
        case Some(game) =>
          //TODO factor out doing timeouts and make it also remove any accepts with timed out users
          //And same for logouts
          val now = Timestamp.get
          game.logins.doTimeouts(now)
          if(!game.logins.heartbeat(user,gameAuth,now))
            Failure(new Exception("Not joined or timed out with the open game with this id."))
          else if(game.creator != Some(user))
            Failure(new Exception("Not the creator of the game."))
          else {
            //TODO should this be distingushed somehow from a timeout for the rejected?
            Success(game.logins.logoutUser(opponent,now))
          }
      }
    }
  }


  /* Actually begin an open game and convert it into an active game */
  private def beginGame(meta: GameMetadata, logins: LoginTracker, gUser: Username, sUser: Username): Future[Unit] = {
    //Write metadata to DB
    val id = meta.id

    //Update metadata with new information
    var now = Timestamp.get
    var newMeta = meta.copy(
      startTime = meta.startTime.orElse(Some(now)),
      gUser = Some(gUser),
      sUser = Some(sUser),
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

        val game = ActiveGameData(
          logins = logins,
          meta = newMeta,
          moves = moves,
          moveStartTime = now,
          gTimeThisMove = computeTimeLeft(newMeta.gTC,moves,GOLD),
          sTimeThisMove = computeTimeLeft(newMeta.sTC,moves,SILV),
          game = initGameFromMoves(moves)
        )
        activeGames = activeGames + (id -> game)
      }
    }
  }

  /* Compute the amount of time on a player clock given the move history of the game */
  private def computeTimeLeft(tc: TimeControl, moves: List[MoveInfo], player: Player): Double = {
    val timeUsageHistory =
      moves.zipWithIndex
        .filter{ case (x,i) => (i % 2 == 0) == (player == GOLD) }
        .map(_._1)
        .map(info => info.time - info.start)
    tc.timeLeftFromHistory(timeUsageHistory)
  }

  /* Initialize a game from the given move history list */
  private def initGameFromMoves(moves: List[MoveInfo]): Game = {
    var game = new Game()
    moves.foreach { move =>
      game = game.parseAndMakeMove(move.move, StandardNotation).get
    }
    game
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
  private def adjournedResult: GameResult =
    GameResult(
      winner = None,
      reason = EndingReason.ADJOURNED,
      endTime = Timestamp.get
    )

}

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
        (TimeControl.apply _).tupled.apply(gTC),
        (TimeControl.apply _).tupled.apply(sTC),
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
