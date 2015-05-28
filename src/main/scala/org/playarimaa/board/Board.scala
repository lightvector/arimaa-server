package org.playarimaa.board
import org.playarimaa.util._

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

  /** Make the specified placements, returning the new board.
    * Returns Error if placements are not on unique empty locations
    * OR if the resulting position has an unguarded piece on a trap
    */
  def place(placements: Seq[Placement]): Result[Board] = {
    val newPieces = placements.foldLeft(pieces) { (newPieces: Map[Location,Piece], placement: Placement) =>
      if(this(placement.dest) != Empty)
        return Error("Invalid placement " + placement + " in: " + placements.mkString(" "))
      newPieces + (placement.dest -> placement.piece)
    }
    val (newBoard, caps) = new Board(newPieces, player, stepsLeft).resolveCaps
    if(!caps.isEmpty)
      Error(
        "Invalid placements: " + placements.mkString(" ") +
          ", unguarded piece(s) on trap: " + caps.mkString(" ")
      )
    else Ok(newBoard)
  }

  def step(step: Step): Result[Board] = {
    //TODO
    Error("unimplemented")
  }

  def resolveCaps: (Board, List[Capture]) = {
    //TODO
    (this,List())
  }

  def endTurn: Board =
    new Board(pieces, player.flip, Board.STEPS_PER_TURN)

}

object Board {
  val SIZE = 8
  val STEPS_PER_TURN = 4
  val TRAPS = List(
    Location(2,2),
    Location(5,2),
    Location(2,5),
    Location(5,5)
  )

  def isOutOfBounds(loc: Location): Boolean =
    loc.x < 0 || loc.x >= SIZE || loc.y < 0 || loc.y >= SIZE
}
