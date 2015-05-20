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
    if(loc.isOffBoard)
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

  //True if there are pieces at loc0 and loc1 owned by opposite players where loc1 is bigger than loc0
  def isDominatedBy(loc0: Location, loc1: Location): Boolean =
    this(loc0) match {
      case Empty | OffBoard => false
      case HasPiece(piece0) =>
        this(loc1) match {
          case Empty | OffBoard => false
          case HasPiece(piece1) =>
            piece0.owner != piece1.owner && piece0.pieceType < piece1.pieceType
        }
    }

  def isFrozen(loc: Location): Boolean =
    this(loc) match {
      case Empty | OffBoard => false
      case HasPiece(piece) => {
        loc.existsAdjacent(adj => isDominatedBy(loc,adj)) &&
        loc.forAllAdjacent(adj => !isOwnedBy(adj,piece.owner))
      }
    }

}

object Board {

}
