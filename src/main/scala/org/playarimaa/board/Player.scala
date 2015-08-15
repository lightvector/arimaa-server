package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait Player {
  val char: Char

  def flip: Player =
    this match {
      case GOLD => SILV
      case SILV => GOLD
    }

  override def toString: String =
    "" + char
}

object Player {
  val values = List(GOLD,SILV)

  def apply(c: Char): Player = {
    ofChar(c).get
  }

  def ofChar(c: Char): Try[Player] =
    c match {
      case 'g' => Success(GOLD)
      case 's' => Success(SILV)
      case _   => Failure(new IllegalArgumentException("Unknown player: " + c))
    }

  def ofString(s: String): Try[Player] = {
    if(s.length == 1)
      ofChar(s(0))
    else
      Failure(new IllegalArgumentException("Unknown player: " + s))
  }
}
case object GOLD extends Player {val char = 'g'}
case object SILV extends Player {val char = 's'}
