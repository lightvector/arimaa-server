package org.playarimaa.board
import scala.util.{Try, Success, Failure}

object Game {
  sealed trait EndingReason
  case object GOAL extends EndingReason
  case object ELIMINATION extends EndingReason
  case object IMMOBILIZATION extends EndingReason
}

class Game private (
  val boards: Vector[Board],
  val moves: Vector[Move]
){
  def this() =
    this(Vector(new Board()), Vector())

  def parseAndMakeMove(moveStr: String, notation: Notation): Try[Game] = {
    notation.read(boards.last, moveStr).flatMap { result =>
      val (move,board) = result
      if(boards.last.samePositionAs(board))
        Failure(new IllegalArgumentException("Illegal move (unchanged position): " + moveStr))
      else if(situationOccursTwice(board))
        Failure(new IllegalArgumentException("Illegal move (3x repetition): " + moveStr))
      else
        Success(new Game(boards :+ board, moves :+ move))
    }
  }

  /** Returns true if [board] occurs at least twice in this game's history */
  def situationOccursTwice(board: Board): Boolean = {
    //TODO implement using board.sameSituationAs
    false
  }

  /** Returns the winner of the game based on the current move history, or None if nobody has won */
  def winner: Option[(Player,Game.EndingReason)] = {
    //TODO
    None
  }
}
