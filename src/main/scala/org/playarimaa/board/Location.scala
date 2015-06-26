package org.playarimaa.board
import scala.util.{Try, Success, Failure}

case class Location (x: Int, y: Int) {

  def isTrap : Boolean =
    (x == 2 || x == 5) && (y == 2 || y == 5)

  override def toString: String =
    if(Board.isOutOfBounds(this))
      "OOB" + super.toString
    else
      "" + ('a' + this.x).toChar + this.y

  def apply(dir: Direction): Location =
    Location(x + dir.offset._1, y + dir.offset._2)

  def existsAdjacent( f:(Location => Boolean) ): Boolean =
    f(this(SOUTH)) || f(this(WEST)) || f(this(EAST)) || f(this(NORTH))

  def forAllAdjacent( f:(Location => Boolean) ): Boolean =
    f(this(SOUTH)) && f(this(WEST)) && f(this(EAST)) && f(this(NORTH))
}

object Location {

  def ofString(s: String): Try[Location] = {
    if(s.length != 2
      || s(0) <  'a'
      || s(0) >= ('a' + Board.SIZE)
      || s(1) < '1'
      || s(1) >= ('1' + Board.SIZE)
    ) {
      Failure(new IllegalArgumentException("Error parsing location string: " + s))
    }

    val x = s(0) - 'a'
    val y = s(1) - '1'
    Success(Location(x,y))
  }

  val values: List[Location] = (
    for(y <- 0 to 7; x <- 0 to 7)
    yield Location(x,y)
  )(collection.breakOut)
}
