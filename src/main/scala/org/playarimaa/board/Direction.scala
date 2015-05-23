package org.playarimaa.board
import org.playarimaa.util._

sealed trait Direction {val offset: (Int,Int); val char: Char;}

object Direction {
  val values = List(SOUTH,WEST,EAST,NORTH)

  def ofChar(c: Char): Result[Direction] =
    c match {
      case 's' => Ok(SOUTH)
      case 'w' => Ok(WEST)
      case 'e' => Ok(EAST)
      case 'n' => Ok(NORTH)
      case _   => Error("Unknown direction: " + c)
    }
}
case object SOUTH extends Direction {val offset = ( 0,-1); val char = 's';}
case object WEST  extends Direction {val offset = (-1, 0); val char = 'w';}
case object EAST  extends Direction {val offset = ( 1, 0); val char = 'e';}
case object NORTH extends Direction {val offset = ( 0, 1); val char = 'n';}
