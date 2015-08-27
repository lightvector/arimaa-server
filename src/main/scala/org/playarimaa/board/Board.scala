package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait LocContents
case class HasPiece(piece: Piece) extends LocContents
case object Empty extends LocContents
case object OffBoard extends LocContents

class Board(
  val pieces: Map[Location,Piece],
  val player: Player,
  val stepsLeft: Int
){
  def this() =
    this(Map(),GOLD,Board.STEPS_PER_TURN)

  /** Returns true if [b] has the same configuration of pieces as [this] */
  def samePositionAs(b: Board): Boolean = {
    //TODO: optimize using zobrist?
    pieces == b.pieces
  }

  /** Returns true if [b] represents an identical board state as [this] */
  def sameSituationAs(b: Board): Boolean = {
    //TODO: optimize using zobrist?
    pieces == b.pieces &&
    player == b.player &&
    stepsLeft == b.stepsLeft
  }

  def apply(loc: Location): LocContents = {
    if(Board.isOutOfBounds(loc))
      OffBoard
    else
      pieces.get(loc) match {
        case Some(piece) => HasPiece(piece)
        case None        => Empty
      }
  }

  /** Returns true if there is a piece at [loc] owned by [p] */
  def isOwnedBy(loc: Location, p: Player): Boolean =
    this(loc) match {
      case Empty | OffBoard => false
      case HasPiece(piece) => piece.owner == p
    }

  /** Returns true if there is a piece at [loc] stronger than [pt] */
  def isStrongerThan(loc: Location, pt: PieceType): Boolean =
    this(loc) match {
      case Empty | OffBoard => false
      case HasPiece(piece) => piece.pieceType > pt
    }

  /** Returns true if there is a piece owned by [p] adjacent to [loc] */
  def isGuardedBy(loc: Location, p: Player): Boolean =
    loc.existsAdjacent(adj => isOwnedBy(adj,p))

  /** Returns true if there is a piece owned by [p] stronger than [pt] adjacent to [loc] */
  def isGuardedByStrongerThan(loc: Location, p: Player, pt: PieceType): Boolean =
    loc.existsAdjacent(adj => isOwnedBy(adj,p) && isStrongerThan(adj,pt))

  /** Returns true if there is a frozen piece at [loc] */
  def isFrozen(loc: Location): Boolean =
    this(loc) match {
      case Empty | OffBoard => false
      case HasPiece(piece) => {
        isGuardedByStrongerThan(loc, piece.owner.flip, piece.pieceType) &&
        !isGuardedBy(loc, piece.owner)
      }
    }

  /**
    * Primitive method called to add a new piece to the board.
    * If the location is currently empty, returns a new board with the piece added.
    * Otherwise returns an error.
    */
  def add(piece: Piece, loc: Location): Try[Board] = {
    this(loc) match {
      case OffBoard => Failure(new IllegalArgumentException("Bad location: " + loc))
      case HasPiece(p) => Failure(new IllegalStateException("Square " + loc + " already occupied with " + p))
      case Empty => {
        val newPieces = pieces  + (loc -> piece)
        Success(new Board(newPieces, player, stepsLeft))
      }
    }
  }

  /**
    * Primitive method called to remove a piece from the board.
    * If there is currently the correct piece at the correct location, returns a new board with the piece removed.
    * Otherwise returns an error.
    */
  def remove(piece: Piece, loc: Location): Try[Board] = {
    this(loc) match {
      case OffBoard => Failure(new IllegalArgumentException("Bad location: " + loc))
      case Empty => Failure(new IllegalStateException("Square " + loc + " is empty."))
      case HasPiece(p) => {
        if (p == piece) {
          val newPieces = pieces  - loc
          Success(new Board(newPieces, player, stepsLeft))
        }
        else
          Failure(new IllegalArgumentException("Location " + loc + " has " + p + ", trying to remove " + piece))
      }
    }
  }

  def setPlayer(p: Player): Board = {
    new Board(pieces, p, stepsLeft)
  }

  def setStepsLeft(s: Int): Try[Board] = {
    if (s < 0 || s > Board.STEPS_PER_TURN)
      Failure(new IllegalArgumentException("Invalid steps left: " + s))
    else
      Success(new Board(pieces, player, s))
  }

  /** Make the specified placements, returning the new board.
    * Returns Error if placements are not on unique empty locations
    * OR if the resulting position has an unguarded piece on a trap
    */
  def place(placements: Seq[Placement]): Try[Board] = {
    val newPieces = placements.foldLeft(pieces) { (newPieces: Map[Location,Piece], placement: Placement) =>
      if(this(placement.dest) != Empty)
        return Failure(new IllegalArgumentException("Invalid placement " + placement + " in: " + placements.mkString(" ")))
      newPieces + (placement.dest -> placement.piece)
    }
    val (newBoard, caps) = new Board(newPieces, player, stepsLeft).resolveCaps
    if(!caps.isEmpty)
      Failure(new IllegalArgumentException(
        "Invalid placements: " + placements.mkString(" ") +
          ", unguarded piece(s) on trap: " + caps.mkString(" ")
      ))
    else Success(newBoard)
  }

  /**
    * Validates and performs the setup move in an Arimaa game.
    * @param p which player to place pieces for
    * @param placements the pieces to place
    * @param gameType type of game for determining setup legality
    * @return a new board representing the board position, if successful.  Otherwise Failure.
    */
  def setup(placements: Placements, gameType: GameType): Try[Board] = {
    val allowWholeBoardSetup = gameType.allowWholeBoardSetup
    val allowPartialSetup = gameType.allowPartialSetup
    placements.placements.foreach { placement =>
      if(placement.piece.owner != player)
        return Failure(new IllegalArgumentException("Illegal piece owner: " + placement))
      if(!gameType.allowWholeBoardSetup) {
        if(player match { case GOLD => placement.dest.y > 1  case SILV => placement.dest.y < Board.SIZE-2 })
          return Failure(new IllegalArgumentException("Illegal location: " + placement))
      }
    }
    Board.PIECE_DISTRIBUTION.foreach { case (pt,max) =>
      val count = placements.placements.count(_.piece.pieceType == pt)
      if(count > max || (!allowPartialSetup && count < max))
        return Failure(new IllegalArgumentException("Illegal number of pieces of type: " + pt.lowercaseChar))
    }
    place(placements.placements)
  }

  /** Validates and performs a single step in an Arimaa game.
    * Does NOT resolve captures - this should be handled with a separate call to [resolveCaps]. */
  def step(step: Step): Try[Board] = {
    val piece = step.piece
    val src = step.src
    val dest = step.dest

    if (stepsLeft <= 0)
      return Failure(new IllegalArgumentException("No steps left."))
    if (piece.owner == player && isFrozen(src))
      return Failure(new IllegalArgumentException("Piece at " + src + " is frozen"))
    if (piece.owner == player && piece.pieceType == RAB && !Board.canRabbitGoInDir(player,step.dir))
      return Failure(new IllegalArgumentException("Illegal backwards rabbit step."))

    remove(piece, src).flatMap { board =>
      board.add(piece, dest).flatMap { board =>
        board.setStepsLeft(stepsLeft - 1)
      }
    }
  }

  /** Removes all undefended pieces on traps and returns a list of the resulting captures. */
  def resolveCaps: (Board, List[Capture]) = {
    var captures : List[Capture] = List()
    var newBoard = this

    Board.TRAPS.foreach { trap =>
      newBoard(trap) match {
        case Empty | OffBoard => ()
        case HasPiece(piece) => {
          if (!newBoard.isGuardedBy(trap, piece.owner)) {
            captures = new Capture(piece, trap) :: captures
            newBoard = newBoard.remove(piece, trap).get
          }
        }
      }
    }
    (newBoard, captures)
  }

  /** Flips the player to move and refills the count of available steps that turn */
  def endTurn: Board =
    new Board(pieces, player.flip, Board.STEPS_PER_TURN)


  /** Performs a step and resolves captures without error checking, throwing if there are problems. */
  def stepAndResolveNoCheck(src: Location, dir: Direction): Board = {
    val dest = src(dir)
    val piece = pieces(src)
    val newPieces = (pieces - src) + (dest -> piece)
    val newBoard = new Board(newPieces, player, stepsLeft - 1)
    newBoard.resolveCaps._1
  }

  def toStringAei : String = {
    val returnVal : StringBuilder = new StringBuilder
    returnVal.append("[")
    Location.valuesAei.foreach { loc : Location =>
      this(loc) match {
        case OffBoard => throw new AssertionError("Bad location: " + loc)
        case HasPiece(p) => returnVal.append(p.toChar)
        case Empty => returnVal.append(' ')
      }
    }
    returnVal.append("]")
    returnVal.toString
  }

  /* Standard format for returning in server queries and/or caching in database */
  def toStandardString : String = {
    val returnVal : StringBuilder = new StringBuilder
    var first = true
    Location.rowsFen.foreach { row =>
      if(first)
        first = false
      else
        returnVal.append('/')

      row.foreach { loc : Location =>
        this(loc) match {
          case OffBoard => throw new AssertionError("Bad location: " + loc)
          case HasPiece(p) => returnVal.append(p.toChar)
          case Empty => returnVal.append('.')
        }
      }
    }
    returnVal.toString
  }

  override def toString : String = toStandardString
}

object Board {
  val SIZE = 8
  val STEPS_PER_TURN = 4
  val TRAPS = List( //scalastyle:off magic.number
    Location(2,2),
    Location(5,2),
    Location(2,5),
    Location(5,5)
  )  //scalastyle:on magic.number

  val PIECE_DISTRIBUTION: List[(PieceType,Int)] = List(
    (RAB,8),
    (CAT,2),
    (DOG,2),
    (HOR,2),
    (CAM,1),
    (ELE,1)
  )

  def isOutOfBounds(loc: Location): Boolean =
    loc.x < 0 || loc.x >= SIZE || loc.y < 0 || loc.y >= SIZE

  def isGoal(player: Player, loc: Location): Boolean =
    (player == GOLD && loc.y == SIZE-1) || (player == SILV && loc.y == 0)

  def canRabbitGoInDir(player: Player, dir: Direction): Boolean =
    !(dir == NORTH && player == SILV) && !(dir == SOUTH && player == GOLD)
}
