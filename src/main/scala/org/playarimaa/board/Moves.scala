package org.playarimaa.board
import scala.util.{Try, Success, Failure}

/** Internal representation of a single step. */
case class Step(piece: Piece, src: Location, dir: Direction) {
  def dest: Location =
    src(dir)

  override def toString: String =
    "" + piece + src + dir
}


/** Internal representation of a single piece placement (such as during setup). */
case class Placement(piece: Piece, dest: Location) {
  override def toString: String =
    "" + piece + dest
}

/** Internal utility class representing a piece capture. */
case class Capture(piece: Piece, src: Location) {
  override def toString: String =
    "" + piece + src + "x"
}

/** Internal representation of a move */
sealed trait Move
case class Steps(steps: List[Step]) extends Move
case class Placements(placements: List[Placement]) extends Move

object Placement {
  def apply(s: String): Placement = {
    if (s.length != 3) {
      throw new IllegalArgumentException()
    }
    val newPiece: Piece = Piece(s.charAt(0))
    val newDest: Location = Location(s.substring(1, 3))
    Placement(newPiece, newDest)
  }
}

/** The interface for a parser for a given notation for Arimaa moves */
trait Notation {
  /** Returns the move corresponding to [moveStr] on the current board,
    * and the resulting board position after making the move.
    */
  def read(board: Board, moveStr: String): Try[(Move,Board)]

  /** Returns the string notation corresponding to [move] on the current board. */
  def write(board: Board, move: Move): Try[String]

  /** Returns the string notation corresponding to the given turn */
  def turnString(plyNum: Int): String
}


/** Parser for standard Arimaa notation, ex: "Ec4w hc5s hc4s hc4x Eb4e" */
object StandardNotation extends Notation {
  def read(board: Board, moveStr: String): Try[(Move,Board)] = {
    //TODO
    Failure(new UnsupportedOperationException())
  }

  def write(board: Board, move: Move): Try[String] = {
    //TODO
    Failure(new UnsupportedOperationException())
  }

  def turnString(plyNum: Int): String = {
    if(plyNum % 2 == 0)
      (1 + plyNum / 2) + "g"
    else
      (1 + plyNum / 2) + "s"
  }
}
