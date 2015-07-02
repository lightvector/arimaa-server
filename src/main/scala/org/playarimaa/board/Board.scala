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

  def isOwnedBy(loc: Location, p: Player): Boolean =
    this(loc) match {
      case Empty | OffBoard => false
      case HasPiece(piece) => piece.owner == p
    }
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
        } else {
          Failure(new IllegalArgumentException("Location " + loc + " has " + p + ", trying to remove " + piece))
        }
      }
    }
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

  def step(step: Step): Try[Board] = {
    //TODO
    Failure(new UnsupportedOperationException())
  }

  def resolveCaps: (Board, List[Capture]) = {
    //TODO
    (this,List())
  }

  def endTurn: Board =
    new Board(pieces, player.flip, Board.STEPS_PER_TURN)

  def toStringAei : String = {
    val returnVal : StringBuilder = new StringBuilder
    returnVal.append("[")
    Location.valuesAei foreach {
      ((loc : Location) => {
        this(loc) match {
          case OffBoard => throw new AssertionError("Bad location: " + loc)
          case HasPiece(p) => returnVal.append(p.toChar)
          case Empty => returnVal.append(' ')
        }
      })
    }
    returnVal.append("]")
    returnVal.toString
  }
  
  override def toString = {
    toStringAei
  }
}

object Board {
  val SIZE = 8
  val STEPS_PER_TURN = 4
  /*
  val TRAPS = List(
    Location(2,2),
    Location(5,2),
    Location(2,5),
    Location(5,5)
  )
  */

  def isOutOfBounds(loc: Location): Boolean =
    loc.x < 0 || loc.x >= SIZE || loc.y < 0 || loc.y >= SIZE
}
