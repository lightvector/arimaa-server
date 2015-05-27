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


  def isFrozen(loc: Location): Boolean =
    this(loc) match {
      case Empty | OffBoard => false
      case HasPiece(piece) => {
        isGuardedByStrongerThan(loc, piece.owner.flip, piece.pieceType) &&
        !isGuardedBy(loc, piece.owner)
      }
    }

}

object Board {
  val SIZE = 8

  val TRAPS = List(
    Location(2,2),
    Location(5,2),
    Location(2,5),
    Location(5,5)
  )

  def isOutOfBounds(loc: Location): Boolean =
    loc.x < 0 || loc.x >= SIZE || loc.y < 0 || loc.y >= SIZE
}
