package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait Direction {
  val offset: (Int,Int)
  val char: Char

  override def toString: String =
    char.toString

  def flip: Direction = {
    this match {
      case SOUTH => NORTH
      case NORTH => SOUTH
      case WEST => EAST
      case EAST => WEST
    }
  }
}

object Direction {
  val values = List(SOUTH,WEST,EAST,NORTH)

  def apply(c: Char): Direction = {
    ofChar(c).get
  }

  def ofChar(c: Char): Try[Direction] =
    c match {
      case 's' => Success(SOUTH)
      case 'w' => Success(WEST)
      case 'e' => Success(EAST)
      case 'n' => Success(NORTH)
      case _   => Failure(new IllegalArgumentException("Unknown direction: " + c))
    }
}
case object SOUTH extends Direction {val offset = ( 0,-1); val char = 's';}
case object WEST  extends Direction {val offset = (-1, 0); val char = 'w';}
case object EAST  extends Direction {val offset = ( 1, 0); val char = 'e';}
case object NORTH extends Direction {val offset = ( 0, 1); val char = 'n';}
