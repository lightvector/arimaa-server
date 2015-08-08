package org.playarimaa.server.game
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.language.postfixOps
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
import akka.actor.{Scheduler,Cancellable}
import akka.pattern.{after}


object Games {
  //Time out users from games if they don't heartbeat at least this often
  val INACTIVITY_TIMEOUT = 15.0
  //How often to check all timeouts
  val TIMEOUT_CHECK_PERIOD = 3.0
  val TIMEOUT_CHECK_PERIOD_IF_ERROR = 60.0

  //Clean up a game with no creator after this many seconds if there is nobody in it
  val NO_CREATOR_GAME_CLEANUP_AGE = 600.0
  val STANDARD_TYPE = "standard"

  val gameTable = TableQuery[GameTable]
  val movesTable = TableQuery[MovesTable]


  case class GetData(
    meta: GameMetadata,
    moves: Vector[MoveInfo],
    openGameData: Option[OpenGames.GetData],
    activeGameData: Option[ActiveGames.GetData],
    sequence: Option[Long]
  )
}

class Games(val db: Database, val scheduler: Scheduler)(implicit ec: ExecutionContext) {

  //Properties/invariants maintained by the implementation of this and of OpenGames and ActiveGames:
  //1. Every game is either open or is recorded in the database (or both)
  //2. If a game is neither active nor open, it cannot become active directly, it must be opened first
  //   (and there is no point during the transition open -> active where a game will appear to be not active yet but also no longer open)

  private val openGames = new OpenGames(db)
  private val activeGames = new ActiveGames(db, scheduler)

  //TODO upon creation, should we load adjourned games from the DB and start them?
  //Maybe only if they're postal games (some heuristic based on tc?). Do we want to credit any time for them?

  //Begin timeout loop on initialization
  checkTimeoutLoop

  def createStandardGame(creator: Username, tc: TimeControl, rated: Boolean): Future[(GameID,Auth)] = {
    openGames.reserveNewGameID.map { id =>
      val auth = openGames.createStandardGame(id,creator,tc,rated)
      (id,auth)
    }
  }

  def reopenAdjournedGame(id: GameID): Future[Unit] = {
    //Checking open and then active in this order is important, taking advantage of property #2 above
    //to make sure that no game with this slips through our check due to a race
    if(!openGames.reserveGameID(id))
      Future.failed(new Exception("Could not reserve game id, game with this id already open"))
    else if(activeGames.hasGame(id)) {
      openGames.releaseGameID(id)
      Future.failed(new Exception("Game already active with this id"))
    }
    else
      openGames.reopenAdjournedGame(id)
  }

  /* Applies the effect of heartbeat/login timeouts to all open and active games */
  private def applyLoginTimeouts: Unit = {
    openGames.applyLoginTimeouts
    activeGames.applyLoginTimeouts
  }

  private def checkTimeoutLoop: Unit = {
    Try(applyLoginTimeouts) match {
      case Failure(_) =>
        //TODO log exn
        scheduler.scheduleOnce(Games.TIMEOUT_CHECK_PERIOD_IF_ERROR seconds) { checkTimeoutLoop }
      case Success(()) =>
        scheduler.scheduleOnce(Games.TIMEOUT_CHECK_PERIOD seconds) { checkTimeoutLoop }
    }
  }

  private def tryOpenAndActive[T](id: GameID)(f: OpenGames => Option[Try[T]])(g: ActiveGames => Option[Try[T]]): Try[T] = {
    None.orElse {
      f(openGames)
    }.orElse {
      g(activeGames)
    } match {
      case None => Failure(new Exception("No open or active game with the given id."))
      case Some(x) => x
    }
  }

  /* Attempt to join an open or active game with the specified id */
  def join(user: Username, id: GameID): Try[Auth] = {
    tryOpenAndActive(id)(_.join(user,id))(_.join(user,id))
  }

  /* Attempt to heartbeat an open or active game with the specified id */
  def heartbeat(user: Username, id: GameID, gameAuth: Auth): Try[Unit] = {
    tryOpenAndActive(id)(_.heartbeat(user,id,gameAuth))(_.heartbeat(user,id,gameAuth))
  }

  /* Attempt to leave an open or active game with the specified id */
  def leave(user: Username, id: GameID, gameAuth: Auth): Try[Unit] = {
    tryOpenAndActive(id)(_.leave(user,id,gameAuth))(_.leave(user,id,gameAuth))
  }

  /* Accept the joining of a given opponent and starts the game if necessary */
  def accept(user: Username, id: GameID, gameAuth: Auth, opponent: Username): Try[Unit] = {
    openGames.accept(user,id,gameAuth,opponent).map { successResult =>
      successResult match {
        case None => ()
        case Some(initData) =>
          //Schedule a game to be started, but don't wait on it
          activeGames.addGame(initData).onComplete { result =>
            //Now that the game is active (or failed to start), clear out the open game.
            openGames.clearStartedGame(id)
          }
      }
    }
  }

  /* Reject the joining of a given opponent */
  def reject(user: Username, id: GameID, gameAuth: Auth, opponent: Username): Try[Unit] = {
    openGames.reject(user,id,gameAuth,opponent)
  }

  /* Resign a game */
  def resign(user: Username, id: GameID, gameAuth: Auth): Try[Unit] = {
    activeGames.resign(user,id,gameAuth)
  }

  /* Make a move in a game */
  def move(user: Username, id: GameID, gameAuth: Auth, moveStr: String, plyNum: Int): Try[Unit] = {
    activeGames.move(user,id,gameAuth, moveStr, plyNum: Int)
  }

  /* Get the state of a game */
  def get(id: GameID, minSequenceAndTimeout: Option[(Long,Double)]): Future[Games.GetData] = {
    val timeout = minSequenceAndTimeout.map { case (_,dur) =>
      after(dur seconds,scheduler)(Future.failed(new Exception("Future timed out!")))
    }
    val minSequence = minSequenceAndTimeout.map(_._1)
    def loop: Future[Games.GetData] = {
      if(timeout.exists(_.isCompleted))
        throw new Exception("Done")
      openGames.get(id,minSequence) match {
        case Some(Right(data)) => Future(data)
        case Some(Left(fut)) => fut.flatMap { case () => loop }
        case None =>
          activeGames.get(id,minSequence) match {
            case Some(Right(data)) => Future(data)
            case Some(Left(fut)) => fut.flatMap { case () => loop }
            case None =>
              GameUtils.loadMetaFromDB(db,id).flatMap { meta =>
                GameUtils.loadMovesFromDB(db,id).map { moves =>
                  Games.GetData(
                    meta = meta,
                    moves = moves,
                    openGameData = None,
                    activeGameData = None,
                    sequence = None
                  )
                }
              }
          }
      }
    }
    timeout match {
      case None => loop
      case Some(timeout) =>
        Future.firstCompletedOf(Seq(timeout,loop))
    }
  }
}

object OpenGames {
  case class GetData(
    creator: Option[Username],
    joined: Set[Username],
    users: PlayerArray[Option[Username]],
    accepted: Map[Username,Username]
  )
}

class OpenGames(val db: Database)(implicit ec: ExecutionContext) {

  case class OpenGameData(
    val meta: GameMetadata,
    val moves: Vector[MoveInfo],
    val users: PlayerArray[Option[Username]],
    val creator: Option[Username],
    val creationTime: Timestamp,
    val logins: LoginTracker,
    //A map indicating who has accepted to play who
    var accepted: Map[Username,Username],
    //A flag indicating that this game has been started and will imminently be removed
    var starting: Boolean,

    //Fulfilled and replaced on each update - this is the mechanism by which queries can block and wait for chat activity
    var sequencePromise: Promise[Unit],
    var sequence: Long
  ) {
    //Updates the sequence number and trigger anyone who was waiting on this game state to be updated
    def advanceSequence: Unit = {
      sequence = sequence + 1
      sequencePromise.success(())
      sequencePromise = Promise()
    }

    //Unaccepts anyone who is logged out at the time of this function call
    def filterAcceptedByLogins: Unit = {
      accepted = accepted.filter { case (user1,user2) =>
        logins.isUserLoggedIn(user1) && !logins.isUserLoggedIn(user2)
      }
    }

    def doTimeouts(now: Timestamp): Unit = {
      val loggedOut = logins.doTimeouts(now)
      filterAcceptedByLogins
      if(loggedOut.nonEmpty)
        advanceSequence
    }
  }


  //All open games are listed in this map, and NOT in the database, unless the game
  //is currently in the process of being started and therefore entered into the database.
  private var openGames: Map[GameID,OpenGameData] = Map()
  //For synchronization - to make sure we don't open two games with the same id
  //Reserving an id prevents new games from being opened OR started that have this id.
  private var reservedGameIDs: Set[GameID] = Set()

  /* Try to reserve a game id for a game that is about to be opened, so that nothing else attempts
   * to open a game with this id in the meantime.
   * Returns true and reserves if the id is not already reserved and there is no open game with this id.
   * Returns false otherwise without reserving anything. */
  def reserveGameID(id: GameID): Boolean = this.synchronized {
    if(reservedGameIDs.contains(id) || openGames.contains(id))
      false
    else {
      reservedGameIDs = reservedGameIDs + id
      true
    }
  }

  def releaseGameID(id: GameID): Unit = this.synchronized {
    assert(reservedGameIDs.contains(id))
    reservedGameIDs = reservedGameIDs - id
  }

  /* Same as [reserveGameID] but creates a new id that doesn't collide with anything else */
  def reserveNewGameID: Future[GameID] = {
    val id = RandGen.genGameID
    if(!reserveGameID(id))
      reserveNewGameID
    else {
      doesCollide(id).resultFlatMap { result =>
        result match {
          case Failure(exn) =>
            releaseGameID(id)
            throw exn
          case Success(collides) =>
            if(collides) {
              releaseGameID(id)
              reserveNewGameID
            }
            else
              Future(id)
        }
      }
    }
  }

  /* Returns true asynchronously if this id collides with any existing game in the database or any open game */
  private def doesCollide(id: GameID): Future[Boolean] = {
    this.synchronized {
      assert(reservedGameIDs.contains(id))
      if(openGames.contains(id))
        return Future(true)
    }
    //Otherwise, query the database to check if we've used it for an older game
    val query: Rep[Int] = Games.gameTable.filter(_.id === id).length
    db.run(query.result).map { count => count != 0 }
  }

  /* Creates a new game and joins the game with the creator, unreserving the id after the game is created */
  def createStandardGame(reservedID: GameID, creator: Username, tc: TimeControl, rated: Boolean): Auth = this.synchronized {
    assert(reservedGameIDs.contains(reservedID))
    val now = Timestamp.get
    val meta = GameMetadata(
      id = reservedID,
      numPly = 0,
      startTime = None,
      users = PlayerArray(gold = "", silv = ""),
      tcs = PlayerArray(gold = tc, silv = tc),
      rated = rated,
      gameType = Games.STANDARD_TYPE,
      tags = List(),
      result = GameUtils.adjournedResult(now)
    )
    val game = OpenGameData(
      meta = meta,
      moves = Vector(),
      users = PlayerArray(gold = None, silv = None),
      creator = Some(creator),
      creationTime = now,
      logins = new LoginTracker(Games.INACTIVITY_TIMEOUT),
      accepted = Map(),
      starting = false,
      sequencePromise = Promise(),
      sequence = 0L
    )
    val gameAuth = game.logins.login(creator,now)
    openGames = openGames + (reservedID -> game)
    releaseGameID(reservedID)
    gameAuth
  }

  /* Reopens an existing adjourned game, unreserving the id regardless of success or failure */
  def reopenAdjournedGame(reservedID: GameID): Future[Unit] = {
    //Load any existing metadata and moves for the game.
    val result =
      GameUtils.loadMetaFromDB(db,reservedID).flatMap { meta =>
        GameUtils.loadMovesFromDB(db,reservedID).map { moves =>
          this.synchronized {
            assert(reservedGameIDs.contains(reservedID))
            assert(!openGames.contains(reservedID))
            if(meta.result.winner.nonEmpty || meta.result.reason != EndingReason.ADJOURNED)
              throw new Exception("Game was not adjourned and/or has a winner already")
            val now = Timestamp.get
            val newMeta = meta.copy(result = GameUtils.adjournedResult(now))
            val game = OpenGameData (
              meta = newMeta,
              moves = Vector(),
              users = meta.users.map(Some(_)),
              creator = None,
              creationTime = now,
              logins = new LoginTracker(Games.INACTIVITY_TIMEOUT),
              accepted = Map(),
              starting = false,
              sequencePromise = Promise(),
              sequence = 0L
            )
            openGames = openGames + (reservedID -> game)
          }
        }
      }
    result.resultMap { result =>
      releaseGameID(reservedID)
      result match {
        case Failure(exn) => throw exn
        case Success(()) => ()
      }
    }
  }

  /* Applies the effect of heartbeat timeouts to all open games */
  def applyLoginTimeouts: Unit = this.synchronized {
    val now = Timestamp.get
    openGames = openGames.filter { case (id,game) =>
      //Log out any individual users who have been inactive
      game.doTimeouts(now)
      //Should we keep this game around, or clean it up it due to timeouts?
      val shouldKeep =
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
      //Trigger anyone waiting if we're about to clean up this game
      if(!shouldKeep)
        game.advanceSequence
      shouldKeep
    }
  }

  /* Attempt to join an open game with the specified id.
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def join(user: Username, id: GameID): Option[Try[Auth]] = this.synchronized {
    openGames.get(id).map { game =>
      //If the game has fixed users specified, then only those users can join
      if(game.users.forAll(_.nonEmpty) && game.users.forAll(_ != Some(user)))
        Failure(new Exception("Not one of the players of this game."))
      else {
        val now = Timestamp.get
        game.doTimeouts(now)
        val auth = game.logins.login(user,now)
        game.advanceSequence
        Success(auth)
      }
    }
  }

  /* Returns None if there was no game, Some(Failure(...)) if there was but it the user was not logged in or [f] failed, and
   * Some(Success(...)) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedInOpt[T](user: Username, id: GameID, gameAuth: Auth)(f: OpenGameData => Try[T]): Option[Try[T]] = {
    openGames.get(id).map { game =>
      val now = Timestamp.get
      game.doTimeouts(now)
      if(!game.logins.heartbeat(user,gameAuth,now))
        Failure(new Exception("Not joined or timed out with the open game with this id."))
      else
        f(game)
    }
  }

  /* Returns Failure(...) if there was no game or the user was not logged in or [f] failed, Success(...) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedIn[T](user: Username, id: GameID, gameAuth: Auth)(f: OpenGameData => Try[T]): Try[T] = {
    ifLoggedInOpt(user,id,gameAuth)(f).getOrElse(Failure(new Exception("No open game with the given id.")))
  }

  /* Attempt to heartbeat an open game with the specified id
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def heartbeat(user: Username, id: GameID, gameAuth: Auth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(user,id,gameAuth) { _ => Success(()) }
  }

  /* Attempt to leave an open game with the specified id
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def leave(user: Username, id: GameID, gameAuth: Auth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(user,id,gameAuth) { game =>
      val now = Timestamp.get
      game.logins.logout(user,gameAuth,now)
      game.filterAcceptedByLogins
      game.advanceSequence
      Success(())
    }
  }

  /* Accept the joining of a given opponent and flags an open game as starting with that opponent
   * Returns Failure(...) on failure and Success(None) if acceptance was processed but the game should not begin yet.
   * Returns Success(Some(...)) if a game should begin, and flags the game as starting.
   *
   * NOTE: In the event that a game should begin, one must call [clearStartedGame] after the newly active game is inserted into
   * the database (or otherwise on failure of the game to start for other reasons).
   */
  def accept(user: Username, id: GameID, gameAuth: Auth, opponent: Username): Try[Option[ActiveGames.InitData]] = this.synchronized {
    ifLoggedIn(user,id,gameAuth) { game =>
      //Do nothing if the game is already starting
      if(game.starting)
        Failure(new Exception("Game already starting"))
      else {
        game.accepted = game.accepted + (user -> opponent)
        game.advanceSequence

        val shouldBegin = game.creator match {
          case None => game.accepted.get(user) == Some(opponent) && game.accepted.get(opponent) == Some(user)
          case Some(creator) => user == creator
        }
        if(!shouldBegin)
          Success(None)
        else {
          def otherUser(u: Username): Username = if(u == user) opponent else user
          //Fill in players based on acceptance, randomizing if necessary
          val (gUser,sUser) = (game.users(GOLD), game.users(SILV)) match {
            case (Some(gUser), Some(sUser)) => (gUser,sUser)
            case (None, Some(sUser)) => (otherUser(sUser),sUser)
            case (Some(gUser), None) => (gUser,otherUser(gUser))
            case (None, None) =>
              if(RandGen.genBoolean)
                (user,opponent)
              else
                (opponent,user)
          }
          val users: PlayerArray[Username] = PlayerArray(gold = gUser, silv = sUser)

          //Flag the game as starting
          game.starting = true
          Success(Some(ActiveGames.InitData(game.meta, game.moves, game.logins, users, game.sequence)))
        }
      }
    }
  }

  /* Reject the joining of a given opponent. */
  def reject(user: Username, id: GameID, gameAuth: Auth, opponent: Username): Try[Unit] = this.synchronized {
    ifLoggedIn(user,id,gameAuth) { game =>
      if(game.starting)
        Failure(new Exception("Game already starting"))
      else if(game.creator != Some(user))
        Failure(new Exception("Not the creator of the game."))
      else {
        val now = Timestamp.get
        //TODO should this be distingushed somehow from a timeout for the rejected?
        game.logins.logoutUser(opponent,now)
        game.filterAcceptedByLogins
        game.advanceSequence
        Success(())
      }
    }
  }

  /* Remove from open games a game that was flagged as started by a call to [accept].
   * Do NOT call this function before inserting the game into the database, to preserve the invariant
   * that every game always is either open or recorded in the database.
   */
  def clearStartedGame(id: GameID): Unit = this.synchronized {
    val game = openGames.get(id)
    assert(game.exists(_.starting))
    game.get.advanceSequence
    openGames = openGames - id
  }

  /* Returns None if there is no such game with this id,
   * Left(future) if the game exists but minSequence is not satisfied (with the future being determined
   *  at some future time, including but not necessarily only when minSequence is satisfied)
   * Right(state) otherwise. */
  def get(id: GameID, minSequence: Option[Long]): Option[Either[Future[Unit],Games.GetData]] = this.synchronized {
    openGames.get(id).map { game =>
      if(minSequence.exists(_ > game.sequence))
        Left(game.sequencePromise.future)
      else
        Right(Games.GetData(
          meta = game.meta,
          moves = game.moves,
          openGameData = Some(OpenGames.GetData(
            creator = game.creator,
            joined = game.logins.usersLoggedIn,
            users = game.users,
            accepted = game.accepted
          )),
          activeGameData = None,
          sequence = Some(game.sequence)
        ))
    }
  }

  def hasGame(id: GameID): Boolean = this.synchronized {
    openGames.contains(id)
  }
}


object ActiveGames {
  case class InitData(
    meta: GameMetadata,
    moves: Vector[MoveInfo],
    logins: LoginTracker,
    users: PlayerArray[Username],
    sequence: Long
  )

  case class GetData(
    moveStartTime: Timestamp,
    timeSpent: Double,
    timeThisMove: PlayerArray[Double],
    present: PlayerArray[Boolean]
  )
}


class ActiveGames(val db: Database, val scheduler: Scheduler) (implicit ec: ExecutionContext) {
  case class ActiveGameData(
    val logins: LoginTracker,
    val users: PlayerArray[Username],
    val game: ActiveGame,

    //Fulfilled and replaced on each update - this is the mechanism by which queries can block and wait for chat activity
    var sequencePromise: Promise[Unit],
    var sequence: Long
  ) {
    //Updates the sequence number and trigger anyone who was waiting on this game state to be updated
    def advanceSequence: Unit = {
      sequence = sequence + 1
      sequencePromise.success(())
      sequencePromise = Promise()
    }

    def doTimeouts(now: Timestamp): Unit = {
      val loggedOut = logins.doTimeouts(now)
      if(loggedOut.nonEmpty)
        advanceSequence
    }
  }

  //All active games are in this map, and also any game in this map has an entry in the database
  private var activeGames: Map[GameID,ActiveGameData] = Map()

  def addGame(data: ActiveGames.InitData): Future[Unit] = {
    val id = data.meta.id

    //Update metadata with new information
    var now = Timestamp.get
    var meta: GameMetadata = data.meta.copy(
      startTime = data.meta.startTime.orElse(Some(now)),
      users = data.users,
      result = data.meta.result.copy(endTime = now)
    )

    //Add game to database
    val query: DBIO[Int] = Games.gameTable += meta
    db.run(query).map { case _ =>
      this.synchronized {
        now = Timestamp.get
        meta = meta.copy(
          result = meta.result.copy(
            endTime = now
          )
        )

        //On a game won by timeout, schedule applyLoginTimeouts to happen asynchronously
        //to report the change in the game back to users and clean up the game
        val onTimeout = { () =>
          scheduler.scheduleOnce(0 seconds) {
            applyLoginTimeouts
          }
          ()
        }
        val game = ActiveGameData(
          logins = data.logins,
          users = data.users,
          //Note that this could raise an exception if the moves aren't legal somehow
          game = new ActiveGame(meta,data.moves,now,db,scheduler,onTimeout),
          sequencePromise = Promise(),
          sequence = data.sequence
        )
        activeGames = activeGames + (id -> game)
      }
    }
  }

  /* Applies the effect of heartbeat timeouts to all active games.
   * Also cleans up games that have ended. */
  def applyLoginTimeouts: Unit = this.synchronized {
    val now = Timestamp.get
    activeGames = activeGames.filter { case (id,game) =>
      game.doTimeouts(now)
      //Clean up games that have ended
      //TODO maybe add checking and logging for games that never get cleaned up because their db commits hang forever?
      if(game.game.canCleanup) {
        game.advanceSequence
        false //filter out
      }
      else
        true //keep

    }
  }

  /* Attempt to join an active game with the specified id.
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def join(user: Username, id: GameID): Option[Try[Auth]] = this.synchronized {
    activeGames.get(id).map { game =>
      val now = Timestamp.get
      game.doTimeouts(now)
      if(!game.users.contains(user))
        Failure(new Exception("Not one of the players of this game."))
      else {
        val auth = game.logins.login(user,now)
        game.advanceSequence
        Success(auth)
      }
    }
  }

  /* Returns None if there was no game, Some(Failure(...)) if there was but it the user was not logged in or [f] failed, and
   * Some(Success(...)) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedInOpt[T](user: Username, id: GameID, gameAuth: Auth)(f: ActiveGameData => Try[T]): Option[Try[T]] = {
    activeGames.get(id).map { game =>
      val now = Timestamp.get
      game.doTimeouts(now)
      if(!game.users.contains(user))
        Failure(new Exception("Not one of the players of this game."))
      else if(!game.logins.heartbeat(user,gameAuth,now))
        Failure(new Exception("Not joined or timed out with the active game with this id."))
      else
        f(game)
    }
  }

  /* Returns Failure(...) if there was no game or the user was not logged in or [f] failed, Success(...) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedIn[T](user: Username, id: GameID, gameAuth: Auth)(f: ActiveGameData => Try[T]): Try[T] = {
    ifLoggedInOpt(user,id,gameAuth)(f).getOrElse(Failure(new Exception("No active game with the given id.")))
  }

  /* Attempt to heartbeat an active game with the specified id
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def heartbeat(user: Username, id: GameID, gameAuth: Auth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(user,id,gameAuth) { _ => Success(()) }
  }

  /* Attempt to leave an active game with the specified id
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def leave(user: Username, id: GameID, gameAuth: Auth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(user,id,gameAuth) { game =>
      val now = Timestamp.get
      game.logins.logout(user,gameAuth,now)
      game.advanceSequence
      Success(())
    }
  }

  private def lookupPlayer(game: ActiveGameData, user: Username): Player = {
    game.users.findPlayer(_ == user).get
  }

  /* Performs [f] not synchronized with the ActiveGames. */
  private def gameActionUnsynced[T](user: Username, id: GameID, gameAuth: Auth)(f: ((ActiveGame,Player)) => Try[T]): Try[T] = {
    val result =
      this.synchronized {
        ifLoggedIn(user,id,gameAuth) { game =>
          val player = lookupPlayer(game,user)
          Success(game,player)
        }
      }
    result.flatMap { case (game,player) =>
      f((game.game,player)).map { x =>
        this.synchronized {
          game.advanceSequence
        }
        x
      }
    }
  }

  /* Resign a game */
  def resign(user: Username, id: GameID, gameAuth: Auth): Try[Unit] = {
    gameActionUnsynced(user,id,gameAuth) { case (game,player) =>
      game.resign(player)
    }
  }

  /* Make a move in a game */
  def move(user: Username, id: GameID, gameAuth: Auth, moveStr: String, plyNum: Int): Try[Unit] = this.synchronized {
    gameActionUnsynced(user,id,gameAuth) { case (game,player) =>
      game.move(moveStr,player,plyNum)
    }
  }

  /* Returns None if there is no such game with this id,
   * Left(future) if the game exists but minSequence is not satisfied (with the future being determined
   *  at some future time, including but not necessarily only when minSequence is satisfied)
   * Right(state) otherwise. */
  def get(id: GameID, minSequence: Option[Long]): Option[Either[Future[Unit],Games.GetData]] = this.synchronized {
    activeGames.get(id).map { game =>
      if(minSequence.exists(_ > game.sequence))
        Left(game.sequencePromise.future)
      else {
        val present = game.users.map { user => game.logins.isUserLoggedIn(user) }
        val (meta,moves,activeGameData) = game.game.getActiveGetData(present)
        Right(Games.GetData(
          meta = meta,
          moves = moves,
          openGameData = None,
          activeGameData = Some(activeGameData),
          sequence = Some(game.sequence)
        ))
      }
    }
  }

  def hasGame(id: GameID): Boolean = this.synchronized {
    activeGames.contains(id)
  }
}

/* All the non-login-related state for a single active game. */
class ActiveGame(
  private var meta: GameMetadata,
  private var moves: Vector[MoveInfo],
  val initNow: Timestamp,
  val db: Database,
  val scheduler: Scheduler,
  val onTimeout: (() => Unit)
)(implicit ec: ExecutionContext) {
  val notation: Notation = StandardNotation

  //Time of the start of the current move
  private var moveStartTime: Timestamp = initNow
  //Time left for each player at the start of the move
  private var timeThisMove: PlayerArray[Double] =
    meta.tcs.mapi { case (player,tc) => GameUtils.computeTimeLeft(tc,moves,player) }
  //The game object itself
  private var game: Game = GameUtils.initGameFromMoves(moves,notation)

  //Synchronization and tracking state ------------------------------------------------------

  //The number of moves we've sent to be written to the database
  private var movesWritten: Int = moves.length
  //The last timeout event we've scheduled to happen
  private var timeoutEvent: Option[Cancellable] = None
  //Future that represent the finishing of writing metadata and moves to the DB
  private var metaSaveFinished: Future[Unit] = Future(())
  private var moveSaveFinished: Future[Unit] = Future(())

  scheduleNextTimeout(initNow)

  def getActiveGetData(present: PlayerArray[Boolean]): (GameMetadata,Vector[MoveInfo],ActiveGames.GetData) = this.synchronized {
    ( meta,
      moves,
      ActiveGames.GetData(
        moveStartTime = moveStartTime,
        timeSpent = Timestamp.get - moveStartTime,
        timeThisMove = timeThisMove,
        present = present
      )
    )
  }

  def getNextPlayer: Player = GameUtils.nextPlayer(meta.numPly)

  def saveMetaToDB: Unit = this.synchronized {
    val metaToSave = meta
    metaSaveFinished = metaSaveFinished.resultMap { _ =>
      val query: DBIO[Int] = Games.gameTable.filter(_.id === metaToSave.id).update(metaToSave)
      db.run(query).resultMap { result =>
        result match {
          //TODO log exn
          case Failure(_) => ()
          case Success(numRowsUpdated) =>
            //TODO LOG
            if(numRowsUpdated != 1) {
            }
            ()
        }
      }
    }
  }

  def saveMovesToDB: Unit = this.synchronized {
    if(movesWritten < moves.length) {
      val newMoves = moves.slice(movesWritten,moves.length)
      movesWritten = moves.length
      moveSaveFinished = moveSaveFinished.resultMap { _ =>
        val query: DBIO[Option[Int]] = Games.movesTable ++= newMoves
        db.run(query).resultMap { result =>
          result match {
            //TODO log exn
            case Failure(_) => ()
            case Success(_) => ()
          }
        }
      }
    }
  }

  def canCleanup: Boolean = this.synchronized {
    meta.result.winner.nonEmpty && metaSaveFinished.isCompleted && moveSaveFinished.isCompleted
  }

  private def declareWinner(player: Player, reason: EndingReason, now: Timestamp): Unit = this.synchronized {
    meta = meta.copy(result = GameResult(winner = Some(player), reason = reason, endTime = now))
    timeoutEvent.foreach(_.cancel)
    saveMetaToDB
  }

  private def scheduleNextTimeout(now: Timestamp): Unit = this.synchronized {
    timeoutEvent.foreach(_.cancel)
    val player = getNextPlayer
    val tc = meta.tcs(player)
    val timeSpent = now - moveStartTime
    val timeAtStartOfTurn = timeThisMove(player)
    val secondsUntilTimeout = tc.timeLeftInCurrentTurn(timeAtStartOfTurn, timeSpent, meta.numPly)
    val plyNum = meta.numPly
    timeoutEvent = Some(scheduler.scheduleOnce(secondsUntilTimeout seconds) {
      if(meta.numPly == plyNum)
        doLoseByTimeout(Timestamp.get)
    })
  }

  /* Directly set the game result for a timeout, unless the game is already ended for another reason. */
  private def doLoseByTimeout(now: Timestamp): Unit = this.synchronized {
    if(meta.result.winner.isEmpty) {
      declareWinner(getNextPlayer.flip, EndingReason.TIME, now)
      onTimeout()
    }
  }

  /* Returns true if a timeout occured and sets the game result */
  private def tryLoseByTimeout(timeLeft: Double, timeSpent: Double, now: Timestamp): Boolean = this.synchronized {
    val player = getNextPlayer
    val tc = meta.tcs(player)
    if(tc.isOutOfTime(timeLeft, timeSpent)) {
      doLoseByTimeout(now)
      true
    }
    else
      false
  }

  private def timeLeftAndSpent(now: Timestamp): (Double,Double) = this.synchronized {
    val player = getNextPlayer
    val tc = meta.tcs(player)
    val timeSpent = now - moveStartTime
    val timeAtStartOfTurn = timeThisMove(player)
    val timeLeft = tc.timeLeftAfterMove(timeAtStartOfTurn, timeSpent, meta.numPly)
    (timeLeft,timeSpent)
  }

  def resign(player: Player): Try[Unit] = this.synchronized {
    if(meta.result.winner.nonEmpty)
      Failure(new Exception("Game is over"))
    else {
      val now = Timestamp.get
      val (timeLeft,timeSpent) = timeLeftAndSpent(now)
      if(tryLoseByTimeout(timeLeft,timeSpent,now))
        Failure(new Exception("Game is over"))
      else {
        declareWinner(player.flip, EndingReason.RESIGNATION, now)
        Success(())
      }
    }
  }

  def move(moveStr: String, player: Player, plyNum: Int): Try[Unit] = this.synchronized {
    if(meta.result.winner.nonEmpty)
      Failure(new Exception("Game is over"))
    else if(player != getNextPlayer)
      Failure(new Exception("Tried to make move for " + player + " but current turn is " + notation.turnString(meta.numPly)))
    else if(plyNum != meta.numPly)
      Failure(new Exception("Tried to make move for turn " + notation.turnString(plyNum) + " but current turn is " + notation.turnString(meta.numPly)))
    else {
      game.parseAndMakeMove(moveStr, notation).flatMap { newGame =>
        val now = Timestamp.get
        val (timeLeft,timeSpent) = timeLeftAndSpent(now)
        if(tryLoseByTimeout(timeLeft,timeSpent,now))
          Failure(new Exception("Game is over"))
        else {
          meta = meta.copy(
            numPly = plyNum+1,
            result = meta.result.copy(endTime = now)
          )
          moves = moves :+ MoveInfo(
            gameID = meta.id,
            ply = plyNum,
            move = moveStr,
            time = now,
            start = moveStartTime
          )
          moveStartTime = now
          timeThisMove = timeThisMove + (player -> timeLeft)
          game = newGame

          saveMetaToDB
          saveMovesToDB

          game.winner match {
            case None => ()
            case Some((winner,why)) =>
              val reason = why match {
                case Game.GOAL => EndingReason.GOAL
                case Game.ELIMINATION => EndingReason.ELIMINATION
                case Game.IMMOBILIZATION => EndingReason.IMMOBILIZATION
              }
              declareWinner(winner, reason, now)
          }
          Success(())
        }
      }
    }
  }

}




object GameUtils {

  /* Compute the amount of time on a player clock given the move history of the game */
  def computeTimeLeft(tc: TimeControl, moves: Seq[MoveInfo], player: Player): Double = {
    val timeUsageHistory =
      moves.zipWithIndex
        .filter{ case (x,i) => (i % 2 == 0) == (player == GOLD) }
        .map(_._1)
        .map(info => info.time - info.start)
    tc.timeLeftFromHistory(timeUsageHistory)
  }

  /* Initialize a game from the given move history list */
  def initGameFromMoves(moves: Seq[MoveInfo], notation: Notation): Game = {
    var game = new Game()
    moves.foreach { move =>
      game = game.parseAndMakeMove(move.move, notation).get
    }
    game
  }

  def loadMetaFromDB(db: Database, id: GameID)(implicit ec: ExecutionContext): Future[GameMetadata] = {
    val query = Games.gameTable.filter(_.id === id)
    db.run(query.result).map(_.toList).map { metas =>
      metas match {
        case Nil => throw new Exception("No game found with the given id")
        case _ :: _ :: _ => throw new Exception("More than one game with the given id")
        case meta :: Nil => meta
      }
    }
  }

  /* Load all existing moves for a game from the database */
  def loadMovesFromDB(db: Database, id: GameID)(implicit ec: ExecutionContext): Future[Vector[MoveInfo]] = {
    val query = Games.movesTable.filter(_.gameID === id).sortBy(_.ply)
    db.run(query.result).map(_.toVector)
  }

  /* The next player to play, based on the number of half-moves played */
  def nextPlayer(numPly: Int): Player = {
    if(numPly % 2 == 0)
      GOLD
    else
      SILV
  }

  /* The game result entered into the database for any unfinished game so that in case the
   * server goes down, any game active at that time appears adjourned without a winner. */
  def adjournedResult(now: Timestamp): GameResult =
    GameResult(
      winner = None,
      reason = EndingReason.ADJOURNED,
      endTime = now
    )
}

case class GameMetadata(
  id: GameID,
  numPly: Int,
  startTime: Option[Timestamp],
  users: PlayerArray[Username],
  tcs: PlayerArray[TimeControl],
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
  def gUser = column[Username]("gUser")
  def sUser = column[Username]("sUser")

  def gInitialTime = column[Int]("gInitialTime")
  def gIncrement = column[Int]("gIncrement")
  def gDelay = column[Int]("gDelay")
  def gMaxReserve = column[Option[Int]]("gMaxReserve")
  def gMaxMoveTime = column[Option[Int]]("gMaxMoveTime")
  def gOvertimeAfter = column[Option[Int]]("gOvertimeAfter")

  def sInitialTime = column[Int]("sInitialTime")
  def sIncrement = column[Int]("sIncrement")
  def sDelay = column[Int]("sDelay")
  def sMaxReserve = column[Option[Int]]("sMaxReserve")
  def sMaxMoveTime = column[Option[Int]]("sMaxMoveTime")
  def sOvertimeAfter = column[Option[Int]]("sOvertimeAfter")

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
      GameMetadata(id,numPly,startTime,
        PlayerArray(gold = gUser, silv = sUser),
        PlayerArray(
          gold = (TimeControl.apply _).tupled.apply(gTC),
          silv = (TimeControl.apply _).tupled.apply(sTC)
        ),
        rated,gameType,tags,
        GameResult.tupled.apply(result)
      )
    },
    //Scala object -> Database shape
    { g: GameMetadata =>
      Some((
        g.id,g.numPly,g.startTime,g.users(GOLD),g.users(SILV),
        TimeControl.unapply(g.tcs(GOLD)).get,
        TimeControl.unapply(g.tcs(SILV)).get,
        g.rated,g.gameType,g.tags,
        GameResult.unapply(g.result).get
      ))
    }
  )
}
