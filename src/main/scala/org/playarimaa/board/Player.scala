package org.playarimaa.board
import org.playarimaa.util._

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

  def ofChar(c: Char): Result[Player] =
    c match {
      case 'g' => Ok(GOLD)
      case 's' => Ok(SILV)
      case _   => Error("Unknown player: " + c)
    }
}
case object GOLD extends Player {val char = 'g'}
case object SILV extends Player {val char = 's'}
