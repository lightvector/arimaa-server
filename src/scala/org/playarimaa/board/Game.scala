package org.playarimaa.board
import org.playarimaa.util._

class Game private (
  val boards: Vector[Board],
  val moves: Vector[Move]
){
  def this() =
    this(Vector(new Board()), Vector())

  def parseAndMakeMove(moveStr: String, notation: Notation): Result[Game] = {
    notation.read(boards.last, moveStr).flatMap { result =>
      val (move,board) = result
      if(boards.last.samePositionAs(board))
        Error("Illegal move (unchanged position): " + moveStr)
      else if(situationOccursTwice(board))
        Error("Illegal move (3x repetition): " + moveStr)
      else
        Ok(new Game(boards :+ board, moves :+ move))
    }
  }

  /** Returns true if [board] occurs at least twice in this game's history */
  def situationOccursTwice(board: Board): Boolean = {
    //TODO implement using board.sameSituationAs
    false
  }

  /** Returns the winner of the game based on the current move history, or None if nobody has won */
  def winner: Option[Player] = {
    //TODO
    None
  }
}
