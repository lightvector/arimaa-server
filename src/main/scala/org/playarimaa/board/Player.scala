package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait Player {
  val char: Char

  def flip: Player =
    this match {
      case GOLD => SILV
      case SILV => GOLD
    }
}

object Player {
  val values = List(GOLD,SILV)

  def ofChar(c: Char): Try[Player] =
    c match {
      case 'g' => Success(GOLD)
      case 's' => Success(SILV)
      case _   => Failure(new IllegalArgumentException("Unknown player: " + c))
    }
}
case object GOLD extends Player {val char = 'g'}
case object SILV extends Player {val char = 's'}
