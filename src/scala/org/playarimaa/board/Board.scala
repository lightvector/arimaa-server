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
        case None       => Empty
      }
  }
}

object Board {

}
