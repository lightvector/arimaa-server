package org.playarimaa.board
import org.playarimaa.util._

/** Internal representation of a single step. */
case class Step(src: Location, dir: Direction) {
  def dest: Location =
    src(dir)

  override def toString: String =
    src.toString + dir
}


/** Internal representation of a single piece placement (such as during setup). */
case class Placement(piece: Piece, dest: Location) {
  override def toString: String =
    piece.toString + dest
}

/** Internal utility class representing a piece capture. */
case class Capture(piece: Piece, src: Location) {
  override def toString: String =
    piece.toString + src + "x"
}

/** Internal representation of a move */
sealed trait Move
case class Steps(steps: List[Step]) extends Move
case class Placements(placements: List[Placement]) extends Move


/** The interface for a parser for a given notation for Arimaa moves */
trait Notation {
  /** Returns the move corresponding to [moveStr] on the current board,
    * and the resulting board position after making the move.
    */
  def read(board: Board, moveStr: String): Result[(Move,Board)]

  /** Returns the string notation corresponding to [move] on the current board. */
  def write(board: Board, move: Move): Result[String]
}


/** Parser for standard Arimaa notation, ex: "Ec4w hc5s hc4s hc4x Eb4e" */
object StandardNotation extends Notation {
  def read(board: Board, moveStr: String): Result[(Move,Board)] = {
    //TODO
    Error("unimplemented")
  }

  def write(board: Board, move: Move): Result[String] = {
    //TODO
    Error("unimplemented")
  }
}
