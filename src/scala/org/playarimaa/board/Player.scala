package org.playarimaa.board
import org.playarimaa.util._

sealed trait Player {val char: Char}

object Player {
  val values = List(GOLD,SILV)

  def flip(p: Player): Player =
    p match {
      case GOLD => SILV
      case SILV => GOLD
    }

  def ofChar(c: Char): Result[Player] =
    c match {
      case 'g' => Ok(GOLD)
      case 's' => Ok(SILV)
      case _   => Error("Unknown player: " + c)
    }
}
case object GOLD extends Player {val char = 'g'}
case object SILV extends Player {val char = 's'}
