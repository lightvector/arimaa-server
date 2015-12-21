package org.playarimaa.server.game
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt, DurationDouble}
import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import org.playarimaa.server.DatabaseConfig.driver.api._
import slick.lifted.{Query,PrimaryKey,ProvenShape}
import org.slf4j.{Logger, LoggerFactory}
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.RandGen
import org.playarimaa.server.LoginTracker
import org.playarimaa.server.Accounts
import org.playarimaa.server.Rating
import org.playarimaa.server.SimpleUserInfo
import org.playarimaa.server.Utils._
import org.playarimaa.board.{Player,GOLD,SILV}
import org.playarimaa.board.{Board,Game,Notation,StandardNotation,GameType}
import akka.actor.{Scheduler,Cancellable}
import akka.pattern.{after}


object Games {
  //Time out users from games if they don't heartbeat at least this often
  val INACTIVITY_TIMEOUT = 15.0
  //How often to check all timeouts
  val TIMEOUT_CHECK_PERIOD = 3.0
  val TIMEOUT_CHECK_PERIOD_IF_ERROR = 60.0

  //Default and max timeout for "get" function
  val GET_DEFAULT_TIMEOUT = 20.0
  val GET_MAX_TIMEOUT = 120.0

  //Clean up a game with no creator after this many seconds if there is nobody in it
  val NO_CREATOR_GAME_CLEANUP_AGE = 600.0

  //Default value and cap on games returned in one search query
  val DEFAULT_SEARCH_LIMIT = 50
  val MAX_SEARCH_LIMIT = 1000

  //Sequence number that game starts on
  val INITIAL_SEQUENCE = 0L

  val gameTable = TableQuery[GameTable]
  val movesTable = TableQuery[MovesTable]


  case class GetMetadata(
    meta: GameMetadata,
    openGameData: Option[OpenGames.GetData],
    activeGameData: Option[ActiveGames.GetData],
    sequence: Option[Long]
  )
  case class GetData(
    meta: GetMetadata,
    moves: Vector[MoveInfo]
  )

  case class SearchParams(
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

    includeUncounted: Option[Boolean],

    limit: Option[Int]
  ) {
    def getLimit : Int = math.min(Games.MAX_SEARCH_LIMIT, math.max(0,limit.getOrElse(Games.DEFAULT_SEARCH_LIMIT)))
  }
}

class Games(val db: Database, val parentLogins: LoginTracker, val scheduler: Scheduler,
  val accounts: Accounts, val serverInstanceID: Long)(implicit ec: ExecutionContext) {

  //Properties/invariants maintained by the implementation of this and of OpenGames and ActiveGames:
  //1. Every game is either open or is recorded in the database (or both)
  //2. If a game is neither active nor open, it cannot become active directly, it must be opened first
  //   (and there is no point during the transition open -> active where a game will appear to be not active yet but also no longer open)

  private val openGames = new OpenGames(db,parentLogins,accounts,serverInstanceID)
  private val activeGames = new ActiveGames(db,scheduler,accounts,serverInstanceID)
  val logger =  LoggerFactory.getLogger(getClass)

  //TODO upon creation, should we load interrupted games from the DB and start them?
  //Maybe only if they're postal games (some heuristic based on tc?). Do we want to credit any time for them?

  //Begin timeout loop on initialization
  checkTimeoutLoop()

  def createStandardGame(
    creator: SimpleUserInfo,
    siteAuth: SiteAuth,
    tc: TimeControl,
    rated: Boolean,
    gUser: Option[SimpleUserInfo],
    sUser: Option[SimpleUserInfo]
  ): Future[(GameID,GameAuth)] = {
    openGames.reserveNewGameID.map { id =>
      val gameAuth = openGames.createStandardGame(id,creator,siteAuth,tc,rated,gUser,sUser)
      (id,gameAuth)
    }
  }

  def createHandicapGame(
    creator: SimpleUserInfo,
    siteAuth: SiteAuth,
    gTC: TimeControl,
    sTC: TimeControl,
    gUser: Option[SimpleUserInfo],
    sUser: Option[SimpleUserInfo]
  ): Future[(GameID,GameAuth)] = {
    openGames.reserveNewGameID.map { id =>
      val gameAuth = openGames.createHandicapGame(id,creator,siteAuth,gTC,sTC,gUser,sUser)
      (id,gameAuth)
    }
  }

  /* Reopen a game that has no winner if the game has any of the specified ending reasons */
  def reopenUnfinishedGame(id: GameID, allowedReasons: Set[EndingReason]): Future[Unit] = {
    //Checking open and then active in this order is important, taking advantage of property #2 above
    //to make sure that no game with this slips through our check due to a race
    if(!openGames.reserveGameID(id))
      Future.failed(new Exception("Could not reserve game id, game with this id already open"))
    else if(activeGames.hasGame(id)) {
      openGames.releaseGameID(id)
      Future.failed(new Exception("Game already active with this id"))
    }
    else
      openGames.reopenUnfinishedGame(id,allowedReasons)
  }

  /* Returns true if there is any open, active, or finished game with this id */
  def gameExists(id: GameID): Future[Boolean] = {
    if(openGames.gameExists(id) || activeGames.gameExists(id))
      Future.successful(true)
    else
      //TODO cache this to avoid banging on the database all the time?
      GameUtils.gameExists(db,id)
  }

  /* Applies the effect of heartbeat/login timeouts to all open and active games */
  private def applyLoginTimeouts(): Unit = {
    openGames.applyLoginTimeouts()
    activeGames.applyLoginTimeouts()
  }

  private def checkTimeoutLoop(): Unit = {
    //This shouldn't normally throw an exception, but in case it does, we don't want to kill the loop,
    //so we instead catch the exception and log it.
    val nextDelay =
      Try(applyLoginTimeouts()) match {
        case Failure(exn) =>
          logger.error("Error in checkTimeoutLoop from applyLoginTimeouts: " + exn)
          Games.TIMEOUT_CHECK_PERIOD_IF_ERROR
      case Success(()) =>
          Games.TIMEOUT_CHECK_PERIOD
      }
    try {
      scheduler.scheduleOnce(nextDelay seconds) { checkTimeoutLoop() }
      ()
    }
    catch {
      //Thrown when the actorsystem shuts down, ignore
      case _ : IllegalStateException => ()
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
  def join(user: SimpleUserInfo, siteAuth: SiteAuth, id: GameID): Try[GameAuth] = {
    tryOpenAndActive(id)(_.join(user,siteAuth,id))(_.join(user,siteAuth,id))
  }

  /* Attempt to heartbeat an open or active game with the specified id */
  def heartbeat(id: GameID, gameAuth: GameAuth): Try[Unit] = {
    tryOpenAndActive(id)(_.heartbeat(id,gameAuth))(_.heartbeat(id,gameAuth))
  }

  /* Attempt to leave an open or active game with the specified id */
  def leave(id: GameID, gameAuth: GameAuth): Try[Unit] = {
    tryOpenAndActive(id)(_.leave(id,gameAuth))(_.leave(id,gameAuth))
  }

  /* Accept the joining of a given opponent and starts the game if necessary */
  def accept(id: GameID, gameAuth: GameAuth, opponent: SimpleUserInfo): Try[Unit] = {
    openGames.accept(id,gameAuth,opponent).map { successResult =>
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

  /* Decline the joining of a given opponent */
  def decline(id: GameID, gameAuth: GameAuth, opponent: Username): Try[Unit] = {
    openGames.decline(id,gameAuth,opponent)
  }

  /* Resign a game */
  def resign(id: GameID, gameAuth: GameAuth): Try[Unit] = {
    activeGames.resign(id,gameAuth)
  }

  /* Make a move in a game */
  def move(id: GameID, gameAuth: GameAuth, moveStr: String, plyNum: Int): Try[Unit] = {
    activeGames.move(id,gameAuth, moveStr, plyNum: Int)
  }

  /* Get the full state of a game */
  def get(id: GameID, minSequence: Option[Long], timeout: Double): Future[Games.GetData] = {
    val timeoutFut = minSequence.map { _ =>
      after(timeout seconds,scheduler)(get(id, None, 0))
    }
    def loop: Future[Games.GetData] = {
      if(timeoutFut.exists(_.isCompleted))
        throw new Exception("Done")
      openGames.get(id,minSequence) match {
        case Some(Right(data)) => Future.successful(data)
        case Some(Left(fut)) => fut.flatMap { case () => loop }
        case None =>
          activeGames.get(id,minSequence) match {
            case Some(Right(data)) => Future.successful(data)
            case Some(Left(fut)) => fut.flatMap { case () => loop }
            case None =>
              GameUtils.loadMetaFromDB(db,id).flatMap { meta =>
                GameUtils.loadMovesFromDB(db,id).map { moves =>
                  Games.GetData(
                    meta = Games.GetMetadata(
                      meta = meta,
                      openGameData = None,
                      activeGameData = None,
                      sequence = None
                    ),
                    moves = moves
                  )
                }
              }
          }
      }
    }
    timeoutFut match {
      case None => loop
      case Some(timeoutFut) =>
        Future.firstCompletedOf(Seq(timeoutFut,loop))
    }
  }

  /* Get only the metadata associated with a game */
  def getMetadata(id: GameID, minSequence: Option[Long], timeout: Double): Future[Games.GetMetadata] = {
    val timeoutFut = minSequence.map { _ =>
      after(timeout seconds,scheduler)(getMetadata(id,None,0))
    }
    def loop: Future[Games.GetMetadata] = {
      if(timeoutFut.exists(_.isCompleted))
        throw new Exception("Done")
      openGames.get(id,minSequence) match {
        case Some(Right(data)) => Future.successful(data.meta)
        case Some(Left(fut)) => fut.flatMap { case () => loop }
        case None =>
          activeGames.get(id,minSequence) match {
            case Some(Right(data)) => Future.successful(data.meta)
            case Some(Left(fut)) => fut.flatMap { case () => loop }
            case None =>
              GameUtils.loadMetaFromDB(db,id).map { meta =>
                Games.GetMetadata(
                  meta = meta,
                  openGameData = None,
                  activeGameData = None,
                  sequence = None
                )
              }
          }
      }
    }
    timeoutFut match {
      case None => loop
      case Some(timeoutFut) =>
        Future.firstCompletedOf(Seq(timeoutFut,loop))
    }
  }


  def searchMetadata(searchParams: Games.SearchParams): Future[List[Games.GetMetadata]] = {
    if(!searchParams.open && (searchParams.creator.nonEmpty || searchParams.creatorNot.nonEmpty))
      Future.failed(new Exception("Specified \"creator\" or \"creatorNot\" without specifying \"open\" in search query"))
    else
      (searchParams.open, searchParams.active) match {
        case (true,true) => Future.failed(new Exception("Specified both \"open\" and \"active\" in search query"))
        case (true,false) => Future.successful(openGames.searchMetadata(searchParams))
        case (false,true) => Future.successful(activeGames.searchMetadata(searchParams))
        case (false,false) =>
          GameUtils.searchDB(db, searchParams, serverInstanceID).map { metas =>
            metas.map { meta =>
              Games.GetMetadata(
                meta = meta,
                openGameData = None,
                activeGameData = None,
                sequence = None
              )
            }
          }
      }
  }
}

object OpenGames {
  case class GetData(
    creator: Option[SimpleUserInfo],
    joined: List[SimpleUserInfo],
    users: PlayerArray[Option[SimpleUserInfo]],
    accepted: Map[Username,SimpleUserInfo],
    creationTime: Timestamp
  )
}

class OpenGames(val db: Database, val parentLogins: LoginTracker,
  val accounts: Accounts, val serverInstanceID: Long)(implicit ec: ExecutionContext) {

  case class OpenGameData(
    val meta: GameMetadata,
    val moves: Vector[MoveInfo],
    val users: PlayerArray[Option[SimpleUserInfo]],
    val creator: Option[SimpleUserInfo],
    val creationTime: Timestamp,
    val logins: LoginTracker,
    //A map indicating who has accepted to play who
    var accepted: Map[Username,SimpleUserInfo],
    //A flag indicating that this game has been started and will imminently be removed
    var starting: Boolean,

    //Fulfilled and replaced on each update - this is the mechanism by which queries can block and wait for state updates
    var sequencePromise: Promise[Unit],
    var sequence: Long
  ) {
    //Updates the sequence number and trigger anyone who was waiting on this game state to be updated
    def advanceSequence(): Unit = {
      sequence = sequence + 1
      sequencePromise.success(())
      sequencePromise = Promise()
    }

    //Unaccepts anyone who is logged out at the time of this function call
    def filterAcceptedByLogins(): Unit = {
      accepted = accepted.filter { case (user1,user2) =>
        logins.isUserLoggedIn(user1) && !logins.isUserLoggedIn(user2.name)
      }
    }

    def doTimeouts(now: Timestamp): Unit = {
      val loggedOut = logins.doTimeouts(now)
      filterAcceptedByLogins()
      if(loggedOut.nonEmpty)
        advanceSequence()
    }
  }


  //All open games are listed in this map, and NOT in the database, unless the game
  //is currently in the process of being started and therefore entered into the database.
  private var openGames: Map[GameID,OpenGameData] = Map()
  //For synchronization - to make sure we don't open two games with the same id
  //Reserving an id prevents new games from being opened OR started that have this id.
  private var reservedGameIDs: Set[GameID] = Set()

  /* Returns true if there is an open game with this id */
  def gameExists(id: GameID): Boolean = this.synchronized {
    openGames.contains(id)
  }

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
              Future.successful(id)
        }
      }
    }
  }

  /* Returns true asynchronously if this id collides with any existing game in the database or any open game */
  private def doesCollide(id: GameID): Future[Boolean] = {
    this.synchronized {
      assert(reservedGameIDs.contains(id))
      if(openGames.contains(id))
        return Future.successful(true)
    }
    //Otherwise, query the database to check if we've used it for an older game
    val query: Rep[Int] = Games.gameTable.filter(_.id === id).length
    db.run(query.result).map { count => count != 0 }
  }

  /* Creates a new game and joins the game with the creator, unreserving the id after the game is created */
  private def createGame(
    reservedID: GameID,
    creator: SimpleUserInfo,
    siteAuth: SiteAuth,
    tcs: PlayerArray[TimeControl],
    users: PlayerArray[Option[SimpleUserInfo]],
    rated: Boolean,
    gameType: GameType
  ): GameAuth = this.synchronized {
    assert(reservedGameIDs.contains(reservedID))
    val now = Timestamp.get
    val meta = GameMetadata(
      id = reservedID,
      numPly = 0,
      startTime = None,
      users = users.map(_.getOrElse(SimpleUserInfo.blank)),
      tcs = tcs,
      rated = rated,
      postal = tcs.exists(_.isPostal),
      gameType = gameType,
      tags = List(),
      result = GameUtils.unfinishedResult(now),
      position = (new Board()).toStandardString,
      serverInstanceID = serverInstanceID
    )
    val game = OpenGameData(
      meta = meta,
      moves = Vector(),
      users = users,
      creator = Some(creator),
      creationTime = now,
      logins = new LoginTracker(Some(parentLogins),Games.INACTIVITY_TIMEOUT,updateInfosFromParent = false),
      accepted = Map(),
      starting = false,
      sequencePromise = Promise(),
      sequence = Games.INITIAL_SEQUENCE
    )
    val gameAuth = game.logins.login(creator,now,Some(siteAuth))
    openGames = openGames + (reservedID -> game)
    releaseGameID(reservedID)
    gameAuth
  }

  def createStandardGame(
    reservedID: GameID,
    creator: SimpleUserInfo,
    siteAuth: SiteAuth,
    tc: TimeControl,
    rated: Boolean,
    gUser: Option[SimpleUserInfo],
    sUser: Option[SimpleUserInfo]
  ): GameAuth = {
    val users = PlayerArray(gold = gUser, silv = sUser)
    createGame(reservedID, creator, siteAuth, PlayerArray(gold = tc, silv = tc), users, rated, GameType.STANDARD)
  }

  def createHandicapGame(
    reservedID: GameID,
    creator: SimpleUserInfo,
    siteAuth: SiteAuth,
    gTC: TimeControl,
    sTC: TimeControl,
    gUser: Option[SimpleUserInfo],
    sUser: Option[SimpleUserInfo]
  ): GameAuth = {
    val users = PlayerArray(gold = gUser, silv = sUser)
    createGame(reservedID, creator, siteAuth, PlayerArray(gold = gTC, silv = sTC), users, rated = false, GameType.HANDICAP)
  }

  /* Reopens an existing winnerless game if it has one of the specified reasons, unreserving the id regardless of success or failure */
  def reopenUnfinishedGame(reservedID: GameID, allowedReasons: Set[EndingReason]): Future[Unit] = {
    //Load any existing metadata and moves for the game.
    val result =
      GameUtils.loadMetaFromDB(db,reservedID).flatMap { meta =>
        GameUtils.loadMovesFromDB(db,reservedID).map { moves =>
          this.synchronized {
            assert(reservedGameIDs.contains(reservedID))
            assert(!openGames.contains(reservedID))
            if(meta.result.winner.nonEmpty)
              throw new Exception("Game has a winner already")
            if(!allowedReasons.contains(meta.result.reason))
              throw new Exception("Game cannot be restarted because it ended for reason " + meta.result.reason)
            val now = Timestamp.get
            val newMeta = meta.copy(
              result = GameUtils.unfinishedResult(now),
              serverInstanceID = serverInstanceID
            )
            val game = OpenGameData (
              meta = newMeta,
              moves = moves,
              users = meta.users.map(Some(_)),
              creator = None,
              creationTime = now,
              logins = new LoginTracker(Some(parentLogins),Games.INACTIVITY_TIMEOUT,updateInfosFromParent = false),
              accepted = Map(),
              starting = false,
              sequencePromise = Promise(),
              sequence = Games.INITIAL_SEQUENCE
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
  def applyLoginTimeouts(): Unit = this.synchronized {
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
              game.logins.isUserLoggedIn(creator.name)
          }
        }
      //Trigger anyone waiting if we're about to clean up this game
      if(!shouldKeep)
        game.advanceSequence()
      shouldKeep
    }
  }

  /* Attempt to join an open game with the specified id.
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def join(user: SimpleUserInfo, siteAuth: SiteAuth, id: GameID): Option[Try[GameAuth]] = this.synchronized {
    openGames.get(id).map { game =>
      //If the game has fixed users specified, then only those users can join
      if(game.users.forAll(_.nonEmpty) && game.users.forAll(_ != Some(user)))
        Failure(new Exception("Not one of the players of this game."))
      else {
        val now = Timestamp.get
        game.doTimeouts(now)
        val gameAuth = game.logins.login(user,now,Some(siteAuth))
        game.advanceSequence()
        Success(gameAuth)
      }
    }
  }

  /* Returns None if there was no game, Some(Failure(...)) if there was but it the user was not logged in or [f] failed, and
   * Some(Success(...)) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedInOpt[T](id: GameID, gameAuth: GameAuth)(f: (SimpleUserInfo,OpenGameData) => Try[T]): Option[Try[T]] = {
    openGames.get(id).map { game =>
      val now = Timestamp.get
      game.doTimeouts(now)
      game.logins.heartbeatAuth(gameAuth,now) match {
        case None => Failure(new Exception("Not joined or timed out with the open game with this id."))
        case Some(user) => f(user,game)
      }
    }
  }

  /* Returns Failure(...) if there was no game or the user was not logged in or [f] failed, Success(...) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedIn[T](id: GameID, gameAuth: GameAuth)(f: (SimpleUserInfo,OpenGameData) => Try[T]): Try[T] = {
    ifLoggedInOpt(id,gameAuth)(f).getOrElse(Failure(new Exception("No open game with the given id.")))
  }

  /* Attempt to heartbeat an open game with the specified id
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def heartbeat(id: GameID, gameAuth: GameAuth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(id,gameAuth) { case _ => Success(()) }
  }

  /* Attempt to leave an open game with the specified id. Logs out all of the user's auths.
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def leave(id: GameID, gameAuth: GameAuth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(id,gameAuth) { case (user,game) =>
      val now = Timestamp.get
      game.logins.logoutUser(user.name,now)
      game.filterAcceptedByLogins()
      game.advanceSequence()
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
  def accept(id: GameID, gameAuth: GameAuth, opponent: SimpleUserInfo): Try[Option[ActiveGames.InitData]] = this.synchronized {
    ifLoggedIn(id,gameAuth) { case (user,game) =>
      //Do nothing if the game is already starting
      if(game.starting)
        Failure(new Exception("Game already starting"))
      else {
        game.accepted = game.accepted + (user.name -> opponent)
        game.advanceSequence()

        val shouldBegin = game.creator match {
          case None =>
            //Each player accepted one another - the game starts
            val userAccepted = game.accepted.get(user.name)
            val opponentAccepted = game.accepted.get(opponent.name)
            userAccepted.exists(_.name == opponent.name) &&
            opponentAccepted.exists(_.name == user.name)
          case Some(creator) =>
            //The creator accepted - the game starts
            user.name == creator.name
        }

        if(!shouldBegin)
          Success(None)
        else {
          def otherUser(u: SimpleUserInfo): SimpleUserInfo = if(u.name == user.name) opponent else user
          //Fill in players based on acceptance, randomizing if necessary
          val (gUser,sUser) = (game.users(GOLD), game.users(SILV)) match {
            case (Some(gUser), Some(sUser)) => (gUser,sUser)
            case (None, Some(sUser)) => (otherUser(sUser),sUser)
            case (Some(gUser), None) => (gUser,otherUser(gUser))
            case (None, None) => if(RandGen.genBoolean) (user,opponent) else (opponent,user)
          }
          val users: PlayerArray[SimpleUserInfo] = PlayerArray(gold = gUser, silv = sUser)

          //Flag the game as starting
          game.starting = true
          Success(Some(ActiveGames.InitData(game.meta, game.moves, game.logins, users, game.sequence)))
        }
      }
    }
  }

  /* Decline the joining of a given opponent. */
  def decline(id: GameID, gameAuth: GameAuth, opponent: Username): Try[Unit] = this.synchronized {
    ifLoggedIn(id,gameAuth) { case (user,game) =>
      if(game.starting)
        Failure(new Exception("Game already starting"))
      else if(game.creator != Some(user))
        Failure(new Exception("Not the creator of the game."))
      else {
        val now = Timestamp.get
        //TODO should this be distingushed somehow from a timeout for the declined?
        game.logins.logoutUser(opponent,now)
        game.filterAcceptedByLogins()
        game.advanceSequence()
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
    game.get.advanceSequence()
    openGames = openGames - id
  }

  private def metadataOfGame(game: OpenGameData): Games.GetMetadata = {
    Games.GetMetadata(
      meta = game.meta,
      openGameData = Some(OpenGames.GetData(
        creator = game.creator,
        joined = game.logins.usersLoggedIn,
        users = game.users,
        accepted = game.accepted,
        creationTime = game.creationTime
      )),
      activeGameData = None,
      sequence = Some(game.sequence)
    )
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
          meta = metadataOfGame(game),
          moves = game.moves
        ))
    }
  }

  /* Get only the metadata associated with a game */
  def getMetadata(id: GameID): Option[Games.GetMetadata] = this.synchronized {
    openGames.get(id).map { game =>
      metadataOfGame(game)
    }
  }


  def searchMetadata(searchParams: Games.SearchParams): List[Games.GetMetadata] = this.synchronized {
    def matches(game: OpenGameData) = {
      searchParams.rated.forall(_ == game.meta.rated) &&
      searchParams.postal.forall(_ == game.meta.postal) &&
      searchParams.gameType.forall(_ == game.meta.gameType) &&
      searchParams.usersInclude.forall { set => set.forall { username => game.users.exists { user => user.exists(_.name.toLowerCase == username.toLowerCase) } } } &&
      searchParams.gUser.forall { gUser => game.users(GOLD).exists(_.name.toLowerCase == gUser.toLowerCase) } &&
      searchParams.sUser.forall { sUser => game.users(SILV).exists(_.name.toLowerCase == sUser.toLowerCase) } &&
      searchParams.creator.forall { creator => game.creator.exists(_.name.toLowerCase == creator.toLowerCase) } &&
      searchParams.creatorNot.forall { creator => game.creator.forall(_.name.toLowerCase != creator.toLowerCase) } &&
      searchParams.minTime.forall { game.creationTime >= _ } &&
      searchParams.maxTime.forall { game.creationTime <= _ }
    }
    openGames.values.filter(matches).map(metadataOfGame).
      toList.
      sortWith(_.openGameData.get.creationTime > _.openGameData.get.creationTime).
      take(searchParams.getLimit)
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
    users: PlayerArray[SimpleUserInfo],
    sequence: Long
  )

  case class GetData(
    moveStartTime: Timestamp,
    timeSpent: Double,
    clockBeforeTurn: PlayerArray[Double],
    present: PlayerArray[Boolean]
  )
}


class ActiveGames(val db: Database, val scheduler: Scheduler,
  val accounts: Accounts, val serverInstanceID: Long) (implicit ec: ExecutionContext) {
  case class ActiveGameData(
    val logins: LoginTracker,
    val users: PlayerArray[SimpleUserInfo],
    val initMeta: GameMetadata, //initial metadata as of game start
    val game: ActiveGame,

    //Fulfilled and replaced on each update - this is the mechanism by which queries can block and wait for state updates
    var sequencePromise: Promise[Unit],
    var sequence: Long
  ) {
    //Updates the sequence number and trigger anyone who was waiting on this game state to be updated
    def advanceSequence(): Unit = {
      sequence = sequence + 1
      sequencePromise.success(())
      sequencePromise = Promise()
    }

    def doTimeouts(now: Timestamp): Unit = {
      val loggedOut = logins.doTimeouts(now)
      if(loggedOut.nonEmpty)
        advanceSequence()
    }
  }

  //All active games are in this map, and also any game in this map has an entry in the database
  private var activeGames: Map[GameID,ActiveGameData] = Map()
  val logger =  LoggerFactory.getLogger(getClass)

  /* Returns true if there is an active game with this id */
  def gameExists(id: GameID): Boolean = this.synchronized {
    activeGames.contains(id)
  }

  def addGame(data: ActiveGames.InitData): Future[Unit] = {
    val id = data.meta.id

    //Update metadata with new information
    var now = Timestamp.get
    var meta: GameMetadata = data.meta.copy(
      startTime = data.meta.startTime.orElse(Some(now)),
      users = data.users,
      result = data.meta.result.copy(endTime = now),
      serverInstanceID = serverInstanceID
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

        //On a game won by time, schedule applyLoginTimeouts to happen asynchronously
        //to report the change in the game back to users and clean up the game
        val onTimeLoss = { () =>
          scheduler.scheduleOnce(0 seconds) {
            applyLoginTimeouts()
          }
          ()
        }
        //Log out everyone except the two users chosen
        data.logins.logoutAllExcept(data.users.values.map(_.name))

        //And initialize the new state
        val game = ActiveGameData(
          logins = data.logins,
          users = data.users,
          initMeta = meta,
          //Note that this could raise an exception if the moves aren't legal somehow
          game = new ActiveGame(meta,data.moves,now,db,scheduler,accounts,onTimeLoss,logger),
          sequencePromise = Promise(),
          sequence = data.sequence+1 //Add one because the transition from open -> active is a state change
        )

        activeGames = activeGames + (id -> game)
      }
    }
  }

  /* Applies the effect of heartbeat timeouts to all active games.
   * Also cleans up games that have ended. */
  def applyLoginTimeouts(): Unit = this.synchronized {
    val now = Timestamp.get
    activeGames = activeGames.filter { case (id,game) =>
      game.doTimeouts(now)
      //Clean up games that have ended
      //TODO maybe add checking and logging for games that never get cleaned up because their db commits hang forever?
      if(game.game.canCleanup) {
        game.advanceSequence()
        false //filter out
      }
      else
        true //keep

    }
  }

  /* Attempt to join an active game with the specified id.
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def join(user: SimpleUserInfo, siteAuth: SiteAuth, id: GameID): Option[Try[GameAuth]] = this.synchronized {
    activeGames.get(id).map { game =>
      val now = Timestamp.get
      game.doTimeouts(now)
      if(!game.users.contains(user))
        Failure(new Exception("Not one of the players of this game."))
      else {
        val gameAuth = game.logins.login(user,now,Some(siteAuth))
        game.advanceSequence()
        Success(gameAuth)
      }
    }
  }

  /* Returns None if there was no game, Some(Failure(...)) if there was but it the user was not logged in or [f] failed, and
   * Some(Success(...)) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedInOpt[T](id: GameID, gameAuth: GameAuth)(f: (SimpleUserInfo,ActiveGameData) => Try[T]): Option[Try[T]] = {
    activeGames.get(id).map { game =>
      val now = Timestamp.get
      game.doTimeouts(now)
      game.logins.heartbeatAuth(gameAuth,now) match {
        case None => Failure(new Exception("Not joined or timed out with the active game with this id."))
        case Some(user) =>
          if(!game.users.contains(user))
            Failure(new Exception("Not one of the players of this game."))
          else
            f(user,game)
      }
    }
  }

  /* Returns Failure(...) if there was no game or the user was not logged in or [f] failed, Success(...) otherwise.
   * In the case that the user is logged in, heartbeats the user.
   */
  private def ifLoggedIn[T](id: GameID, gameAuth: GameAuth)(f: (SimpleUserInfo,ActiveGameData) => Try[T]): Try[T] = {
    ifLoggedInOpt(id,gameAuth)(f).getOrElse(Failure(new Exception("No active game with the given id.")))
  }

  /* Attempt to heartbeat an active game with the specified id
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def heartbeat(id: GameID, gameAuth: GameAuth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(id,gameAuth) { case _ => Success(()) }
  }

  /* Attempt to leave an active game with the specified id. Logs out all of the user's auths.
   * Returns None if there was no game, Some(Failure(...)) if there was but it failed, and Some(Success(...)) on success.
   */
  def leave(id: GameID, gameAuth: GameAuth): Option[Try[Unit]] = this.synchronized {
    ifLoggedInOpt(id,gameAuth) { case (user,game) =>
      val now = Timestamp.get
      game.logins.logoutUser(user.name,now)
      game.advanceSequence()
      Success(())
    }
  }

  private def lookupPlayer(game: ActiveGameData, user: Username): Player = {
    game.users.findPlayer(_.name == user).get
  }

  /* Performs [f] not synchronized with the ActiveGames. */
  private def gameActionUnsynced[T](id: GameID, gameAuth: GameAuth)(f: (ActiveGame,Player) => Try[T]): Try[T] = {
    val result =
      this.synchronized {
        ifLoggedIn(id,gameAuth) { case (user,game) =>
          val player = lookupPlayer(game,user.name)
          Success((game,player))
        }
      }
    result.flatMap { case (game,player) =>
      f(game.game,player).map { x =>
        this.synchronized {
          game.advanceSequence()
        }
        x
      }
    }
  }

  /* Resign a game */
  def resign(id: GameID, gameAuth: GameAuth): Try[Unit] = {
    gameActionUnsynced(id,gameAuth) { case (game,player) =>
      game.resign(player)
    }
  }

  /* Make a move in a game */
  def move(id: GameID, gameAuth: GameAuth, moveStr: String, plyNum: Int): Try[Unit] = this.synchronized {
    gameActionUnsynced(id,gameAuth) { case (game,player) =>
      game.move(moveStr,player,plyNum)
    }
  }

  private def metadataAndMovesOfGame(game: ActiveGameData): (Games.GetMetadata, Vector[MoveInfo]) = {
    val present = game.users.map { user => game.logins.isUserLoggedIn(user.name) }
    val (meta,moves,activeGameData) = game.game.getActiveGetData(present)
    val gm = Games.GetMetadata(
      meta = meta,
      openGameData = None,
      //This will be None if the game is ended but is not yet cleaned up
      activeGameData = activeGameData,
      //Only provide the sequence number if the game is still going
      sequence = activeGameData.map { case _ => game.sequence }
    )
    (gm,moves)
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
        val present = game.users.map { user => game.logins.isUserLoggedIn(user.name) }
        val (meta,moves,activeGameData) = game.game.getActiveGetData(present)
        val gmeta = Games.GetMetadata(
          meta = meta,
          openGameData = None,
          //This will be None if the game is ended but is not yet cleaned up
          activeGameData = activeGameData,
          //Only provide the sequence number if the game is still going
          sequence = activeGameData.map { case _ => game.sequence }
        )
        Right(Games.GetData(
          meta = gmeta,
          moves = moves
        ))
      }
    }
  }

  private def metadataOfGame(game: ActiveGameData): Games.GetMetadata = {
    val present = game.users.map { user => game.logins.isUserLoggedIn(user.name) }
    val (meta,_,activeGameData) = game.game.getActiveGetData(present)
    Games.GetMetadata(
      meta = meta,
      openGameData = None,
      //This will be None if the game is ended but is not yet cleaned up
      activeGameData = activeGameData,
      //Only provide the sequence number if the game is still going
      sequence = activeGameData.map { case _ => game.sequence }
    )
  }

  /* Get only the metadata associated with a game */
  def getMetadata(id: GameID): Option[Games.GetMetadata] = this.synchronized {
    activeGames.get(id).map { game =>
      metadataOfGame(game)
    }
  }


  def searchMetadata(searchParams: Games.SearchParams): List[Games.GetMetadata] = this.synchronized {
    def matches(game: ActiveGameData) = {
      searchParams.rated.forall(_ == game.initMeta.rated) &&
      searchParams.postal.forall(_ == game.initMeta.postal) &&
      searchParams.gameType.forall(_ == game.initMeta.gameType) &&
      searchParams.usersInclude.forall { set => set.forall { username => game.users.exists { user => user.name.toLowerCase == username.toLowerCase } } } &&
      searchParams.gUser.forall { gUser => game.users(GOLD).name.toLowerCase == gUser.toLowerCase } &&
      searchParams.sUser.forall { sUser => game.users(SILV).name.toLowerCase == sUser.toLowerCase } &&
      searchParams.minTime.forall { game.initMeta.startTime.get >= _ } &&
      searchParams.maxTime.forall { game.initMeta.startTime.get <= _ }
    }
    activeGames.values.filter(matches).map(metadataOfGame).
      toList.
      sortWith(_.meta.startTime.get > _.meta.startTime.get).
      take(searchParams.getLimit)
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
  val accounts: Accounts,
  val onTimeLoss: (() => Unit),
  val logger: Logger //borrows a logger so that we don't create a new one on every game
)(implicit ec: ExecutionContext) {
  val notation: Notation = StandardNotation

  //Time of the start of the current move
  private var moveStartTime: Timestamp = initNow
  //Time left for each player before the start of this turn
  private var clockBeforeTurn: PlayerArray[Double] =
    meta.tcs.mapi { case (player,tc) => GameUtils.computeTimeLeft(tc,moves,player) }
  //The game object itself
  private var game: Game = GameUtils.initGameFromMoves(moves,notation,meta.gameType)

  //Synchronization and tracking state ------------------------------------------------------

  //The number of moves we've sent to be written to the database
  private var movesWritten: Int = moves.length
  //The last timeLossCheck event we've scheduled to happen
  private var timeLossCheckEvent: Option[Cancellable] = None
  //Future that represent the finishing of writing metadata and moves to the DB
  private var metaSaveFinished: Future[Unit] = Future.successful(())
  private var moveSaveFinished: Future[Unit] = Future.successful(())

  scheduleNextTimeLossCheck(initNow)

  def getActiveGetData(present: PlayerArray[Boolean]): (GameMetadata,Vector[MoveInfo],Option[ActiveGames.GetData]) = this.synchronized {
    val activeGetData =
      if(gameOver)
        None
      else
        Some(ActiveGames.GetData(
          moveStartTime = moveStartTime,
          timeSpent = Timestamp.get - moveStartTime,
          clockBeforeTurn = clockBeforeTurn,
          present = present
        ))
    (meta,moves,activeGetData)
  }

  def getNextPlayer: Player = GameUtils.nextPlayer(meta.numPly)

  def saveMetaToDB(): Unit = this.synchronized {
    val metaToSave = meta
    metaSaveFinished = metaSaveFinished.resultFlatMap { _ =>
      val query: DBIO[Int] = Games.gameTable.filter(_.id === metaToSave.id).update(metaToSave)
      db.run(query).resultMap { result =>
        result match {
          case Failure(exn) =>
            logger.error("Error saving game metadata to db: " + exn)
          case Success(numRowsUpdated) =>
            if(numRowsUpdated != 1)
              logger.error("Error saving game metadata to db, " + numRowsUpdated + " row updated when only 1 expected")
        }
      }
    }
  }

  def saveMovesToDB(): Unit = this.synchronized {
    if(movesWritten < moves.length) {
      val newMoves = moves.slice(movesWritten,moves.length)
      movesWritten = moves.length
      moveSaveFinished = moveSaveFinished.resultFlatMap { _ =>
        val query: DBIO[Option[Int]] = Games.movesTable ++= newMoves
        db.run(query).resultMap { result =>
          result match {
            case Failure(exn) =>
              logger.error("Error saving game moves to db: " + exn)
            case Success(None) =>
              logger.error("Error saving game moves to db, no rows inserted")
            case Success(Some(numRowsInserted)) =>
              if(numRowsInserted <= 0)
                logger.error("Error saving game moves to db, " + numRowsInserted + " rows inserted when >= 1 expected")
          }
        }
      }
    }
  }

  def gameOver: Boolean = this.synchronized {
    meta.result.winner.nonEmpty
  }

  def canCleanup: Boolean = this.synchronized {
    gameOver && metaSaveFinished.isCompleted && moveSaveFinished.isCompleted
  }

  private def declareWinner(winner: Player, reason: EndingReason, now: Timestamp): Unit = this.synchronized {
    //Only count a game for statistics and ratings if the player who lost made at least one move
    val countForStats = (winner == GOLD && meta.numPly >= 1) || (winner == SILV && meta.numPly >= 2)

    meta = meta.copy(result = GameResult(winner = Some(winner), reason = reason, endTime = now, countForStats = countForStats))
    timeLossCheckEvent.foreach(_.cancel)
    saveMetaToDB()

    //Update statistics for players, but don't wait for the update
    if(countForStats) {
      val loser = winner.flip
      Future {
        //Do all of these separately to avoid one error preventing other stats from updating
        accounts.updateGameStats(meta.users(winner).name) { wStats =>
          val (wg,ws) = if(winner == GOLD) (1,0) else (0,1)
          val newWStats = wStats.copy(
            numGamesWon = wStats.numGamesWon+1,
            numGamesGold = wStats.numGamesGold+wg,
            numGamesSilv = wStats.numGamesSilv+ws
          )
          newWStats
        }.onFailure { case exn =>
            logger.error("Error updating post-game stats for " + meta.users(winner).name + " :" + exn)
        }

        accounts.updateGameStats(meta.users(loser).name) { lStats =>
          val (lg,ls) = if(loser == GOLD) (1,0) else (0,1)
          val newLStats = lStats.copy(
            numGamesLost = lStats.numGamesLost+1,
            numGamesGold = lStats.numGamesGold+lg,
            numGamesSilv = lStats.numGamesSilv+ls
          )
          newLStats
        }.onFailure { case exn =>
            logger.error("Error updating post-game stats for " + meta.users(loser).name  + " :" + exn)
        }

        if(meta.rated && !meta.users(winner).isGuest && !meta.users(loser).isGuest) {
          accounts.updateGameStats2(meta.users(winner).name, meta.users(loser).name) { case (wStats,lStats) =>
            val (newWRating,newLRating) = Rating.newRatings(wStats.rating,lStats.rating)
            val newWStats = wStats.copy(rating = newWRating)
            val newLStats = lStats.copy(rating = newLRating)
            (newWStats,newLStats)
          }.onFailure { case exn =>
              logger.error("Error updating post-game ratings for " + meta.users(winner).name + ", " + meta.users(loser).name  + " :" + exn)
          }
        }
      }
    }

    ()
  }

  private def scheduleNextTimeLossCheck(now: Timestamp): Unit = this.synchronized {
    timeLossCheckEvent.foreach(_.cancel)
    val player = getNextPlayer
    val tc = meta.tcs(player)
    val timeSpent = now - moveStartTime
    val timeLeftUntilLoss = tc.timeLeftUntilLoss(clockBeforeTurn(player), timeSpent, meta.numPly)
    val plyNum = meta.numPly
    timeLossCheckEvent = Some(scheduler.scheduleOnce(timeLeftUntilLoss seconds) {
      if(meta.numPly == plyNum && player == getNextPlayer)
        doLoseByTime(Timestamp.get)
    })
  }

  /* Directly set the game result for a loss by time, unless the game is already ended for another reason. */
  private def doLoseByTime(now: Timestamp): Unit = this.synchronized {
    if(!gameOver) {
      declareWinner(getNextPlayer.flip, EndingReason.TIME, now)
      onTimeLoss()
    }
  }

  /* Returns true if a loss by time occured and sets the game result */
  private def tryLoseByTime(clock: Double, timeSpent: Double, now: Timestamp): Boolean = this.synchronized {
    val player = getNextPlayer
    val tc = meta.tcs(player)
    if(tc.isOutOfTime(clock, timeSpent)) {
      doLoseByTime(now)
      true
    }
    else
      false
  }

  /* Returns the current value of the player's clock and the time spent this turn so far */
  private def clockAndSpent(now: Timestamp): (Double,Double) = this.synchronized {
    val player = getNextPlayer
    val tc = meta.tcs(player)
    val timeSpent = now - moveStartTime
    val clock = tc.clockAfterTurn(clockBeforeTurn(player), timeSpent, meta.numPly)
    (clock,timeSpent)
  }

  def resign(player: Player): Try[Unit] = this.synchronized {
    if(gameOver)
      Failure(new Exception("Game is over"))
    else {
      val now = Timestamp.get
      val (clock,timeSpent) = clockAndSpent(now)
      if(tryLoseByTime(clock,timeSpent,now))
        Failure(new Exception("Game is over"))
      else {
        declareWinner(player.flip, EndingReason.RESIGNATION, now)
        Success(())
      }
    }
  }

  def move(moveStr: String, player: Player, plyNum: Int): Try[Unit] = this.synchronized {
    if(gameOver)
      Failure(new Exception("Game is over"))
    else if(player != getNextPlayer)
      Failure(new Exception("Tried to make move for " + player + " but current turn is " + notation.turnString(meta.numPly)))
    else if(plyNum != meta.numPly)
      Failure(new Exception("Tried to make move for turn " + notation.turnString(plyNum) + " but current turn is " + notation.turnString(meta.numPly)))
    else {
      //Special case for resign move
      if(moveStr.toLowerCase == "resign" || moveStr.toLowerCase == "resigns")
        resign(player)
      else {
        game.parseAndMakeMove(moveStr, notation).flatMap { newGame =>
          val now = Timestamp.get
          val (clock,timeSpent) = clockAndSpent(now)
          if(tryLoseByTime(clock,timeSpent,now))
            Failure(new Exception("Game is over"))
          else {
            meta = meta.copy(
              numPly = plyNum + 1,
              result = meta.result.copy(endTime = now),
              position = newGame.currentBoardString
            )
            moves = moves :+ MoveInfo(
              gameID = meta.id,
              ply = plyNum,
              move = moveStr,
              time = now,
              start = moveStartTime
            )
            moveStartTime = now
            clockBeforeTurn = clockBeforeTurn + (player -> clock)
            game = newGame

            saveMetaToDB()
            saveMovesToDB()

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

            scheduleNextTimeLossCheck(now)
            Success(())
          }
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
    tc.clockFromHistory(timeUsageHistory)
  }

  /* Initialize a game from the given move history list */
  def initGameFromMoves(moves: Seq[MoveInfo], notation: Notation, gameType: GameType): Game = {
    var game = new Game(gameType)
    moves.foreach { move =>
      game = game.parseAndMakeMove(move.move, notation).get
    }
    game
  }

  /* Returns true if this game exists in the database */
  def gameExists(db: Database, id: GameID)(implicit ec: ExecutionContext): Future[Boolean] = {
    val query: Rep[Boolean] = Games.gameTable.filter(_.id === id).exists
    db.run(query.result).map { x => !x }
  }

  /* Load the metadata for a game from the database */
  def loadMetaFromDB(db: Database, id: GameID)(implicit ec: ExecutionContext): Future[GameMetadata] = {
    val query: Rep[Seq[GameMetadata]] = Games.gameTable.filter(_.id === id)
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
    val query: Rep[Seq[MoveInfo]] = Games.movesTable.filter(_.gameID === id).sortBy(_.ply)
    db.run(query.result).map(_.toVector)
  }

  def searchDB(db: Database, searchParams: Games.SearchParams, serverInstanceID: Long)(implicit ec: ExecutionContext): Future[List[GameMetadata]] = {
    var query = Games.gameTable.filter(_.numPly === 5)
    searchParams.rated.foreach { rated => query = query.filter(_.rated === rated) }
    searchParams.postal.foreach { postal => query = query.filter(_.postal === postal) }
    searchParams.gameType.foreach { gameType => query = query.filter(_.gameType === gameType) }
    searchParams.usersInclude.foreach { set => set.foreach { user => query = query.filter { row => (row.gUser === user) || (row.sUser === user) } } }
    searchParams.gUser.foreach { gUser => query = query.filter(_.gUser === gUser) }
    searchParams.sUser.foreach { sUser => query = query.filter(_.sUser === sUser) }
    searchParams.minTime.foreach { minTime => query = query.filter(_.endTime >= minTime) }
    searchParams.maxTime.foreach { maxTime => query = query.filter(_.endTime <= maxTime) }
    searchParams.includeUncounted match {
      case None | Some(false) => query = query.filter(_.countForStats)
      case Some(true) => ()
    }

    //Filter out games that are active right now. Games active right now are present in the database with ending
    //reason interrupted but were created with this server instance
    query = query.filter { row => row.reason === EndingReason.INTERRUPTED.toString } .filter { row => row.serverInstanceID === serverInstanceID }
    //Reverse chronological sort
    query = query.sortBy(_.endTime.desc)
    //Limit the size of the query results
    val final_query = query.take(math.min(Games.MAX_SEARCH_LIMIT, searchParams.limit.getOrElse(Games.DEFAULT_SEARCH_LIMIT)))
    db.run(query.result).map(_.toList)
  }

  /* The next player to play, based on the number of half-moves played */
  def nextPlayer(numPly: Int): Player = {
    if(numPly % 2 == 0)
      GOLD
    else
      SILV
  }

  /* The game result entered into the database for any unfinished game so that in case the
   * server goes down, any game active at that time appears this way without a winner. */
  def unfinishedResult(now: Timestamp): GameResult =
    GameResult(
      winner = None,
      reason = EndingReason.INTERRUPTED,
      endTime = now,
      //Without a winner, we won't count ratings for this game even if it's rated, and setting this true
      //makes it more easily noticeable on a user's game history
      countForStats = true
    )
}

case class GameMetadata(
  id: GameID,
  numPly: Int,
  startTime: Option[Timestamp],
  users: PlayerArray[SimpleUserInfo],
  tcs: PlayerArray[TimeControl],
  rated: Boolean,
  postal: Boolean,
  gameType: GameType,
  tags: List[String],
  result: GameResult,
  position: String,
  serverInstanceID: Long
)

case class MoveInfo(
  gameID: GameID,
  ply: Int,
  move: String,
  time: Double,
  start: Double
)

class MovesTable(tag: Tag) extends Table[MoveInfo](tag, "movesTable") {
  def gameID : Rep[GameID] = column[GameID]("gameID")
  def ply : Rep[Int] = column[Int]("ply")
  def move : Rep[String] = column[String]("move")
  def time : Rep[Timestamp] = column[Timestamp]("time")
  def start : Rep[Timestamp] = column[Timestamp]("start")

  def * : ProvenShape[MoveInfo] = (gameID, ply, move, time, start) <> (MoveInfo.tupled, MoveInfo.unapply)

  def pk : PrimaryKey = primaryKey("pk_gameID_ply", (gameID, ply))
}

class GameTable(tag: Tag) extends Table[GameMetadata](tag, "gameTable") {
  def id : Rep[GameID] = column[GameID]("id", O.PrimaryKey)
  def numPly : Rep[Int] = column[Int]("numPly")
  def startTime : Rep[Option[Timestamp]] = column[Option[Timestamp]]("startTime")
  def gUser : Rep[Username] = column[Username]("gUser")
  def gRating : Rep[Double] = column[Double]("gRating")
  def gRatingStdev : Rep[Double] = column[Double]("gRatingStdev")
  def gIsBot : Rep[Boolean] = column[Boolean]("gIsBot")
  def gIsGuest : Rep[Boolean] = column[Boolean]("gIsGuest")
  def sUser : Rep[Username] = column[Username]("sUser")
  def sRating : Rep[Double] = column[Double]("sRating")
  def sRatingStdev : Rep[Double] = column[Double]("sRatingStdev")
  def sIsBot : Rep[Boolean] = column[Boolean]("sIsBot")
  def sIsGuest : Rep[Boolean] = column[Boolean]("sIsGuest")

  def gInitialTime : Rep[Double] = column[Double]("gInitialTime")
  def gIncrement : Rep[Double] = column[Double]("gIncrement")
  def gDelay : Rep[Double] = column[Double]("gDelay")
  def gMaxReserve : Rep[Option[Double]] = column[Option[Double]]("gMaxReserve")
  def gMaxMoveTime : Rep[Option[Double]] = column[Option[Double]]("gMaxMoveTime")
  def gOvertimeAfter : Rep[Option[Int]] = column[Option[Int]]("gOvertimeAfter")

  def sInitialTime : Rep[Double] = column[Double]("sInitialTime")
  def sIncrement : Rep[Double] = column[Double]("sIncrement")
  def sDelay : Rep[Double] = column[Double]("sDelay")
  def sMaxReserve : Rep[Option[Double]] = column[Option[Double]]("sMaxReserve")
  def sMaxMoveTime : Rep[Option[Double]] = column[Option[Double]]("sMaxMoveTime")
  def sOvertimeAfter : Rep[Option[Int]] = column[Option[Int]]("sOvertimeAfter")

  def rated : Rep[Boolean] = column[Boolean]("rated")
  def postal : Rep[Boolean] = column[Boolean]("postal")
  def gameType : Rep[String] = column[String]("gameType")
  def tags : Rep[List[String]] = column[List[String]]("tags")

  def winner : Rep[Option[Player]] = column[Option[Player]]("winner")
  def reason : Rep[String] = column[String]("reason")
  def endTime : Rep[Timestamp] = column[Timestamp]("endTime")
  def countForStats : Rep[Boolean] = column[Boolean]("countForStats")

  def position : Rep[String] = column[String]("position")
  def serverInstanceID: Rep[Long] = column[Long]("serverInstanceID")

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
  // implicit val gameTypeMapper = MappedColumnType.base[GameType, String] (
  //   { gt => gt.toString },
  //   { str => GameType.ofString(str).get }
  // )

  def * : ProvenShape[GameMetadata] = (
    //Define database projection shape
    id,numPly,startTime,
    (gUser,gRating,gRatingStdev,gIsBot,gIsGuest),
    (sUser,sRating,sRatingStdev,sIsBot,sIsGuest),
    (gInitialTime,gIncrement,gDelay,gMaxReserve,gMaxMoveTime,gOvertimeAfter),
    (sInitialTime,sIncrement,sDelay,sMaxReserve,sMaxMoveTime,sOvertimeAfter),
    rated,postal,gameType,tags,
    (winner,reason,endTime,countForStats),
    position,
    serverInstanceID
  ).shaped <> (
    //Database shape -> Scala object
    { case (id,numPly,startTime,
      gInfo,
      sInfo,
      gTC,
      sTC,rated,postal,gameType,tags,
      (winner,reason,endTime,countForStats),
      position,
      serverInstanceID) =>
      GameMetadata(id,numPly,startTime,
        PlayerArray(
          gold = SimpleUserInfo.ofDB(gInfo),
          silv = SimpleUserInfo.ofDB(sInfo)
        ),
        PlayerArray(
          gold = (TimeControl.apply _).tupled.apply(gTC),
          silv = (TimeControl.apply _).tupled.apply(sTC)
        ),
        rated,postal,GameType.ofString(gameType).get,tags,
        GameResult.tupled.apply((winner,EndingReason.ofString(reason).get,endTime,countForStats)),
        position,
        serverInstanceID
      )
    },
    //Scala object -> Database shape
    { g: GameMetadata =>
      Some((
        g.id,g.numPly,g.startTime,
        SimpleUserInfo.toDB(g.users(GOLD)),
        SimpleUserInfo.toDB(g.users(SILV)),
        TimeControl.unapply(g.tcs(GOLD)).get,
        TimeControl.unapply(g.tcs(SILV)).get,
        g.rated,g.postal,g.gameType.toString,g.tags,
        (g.result.winner,g.result.reason.toString,g.result.endTime,g.result.countForStats),
        g.position,
        g.serverInstanceID
      ))
    }
  )
}
