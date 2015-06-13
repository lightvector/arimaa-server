package org.playarimaa.board
import org.playarimaa.util._

case class Location (x: Int, y: Int) {

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

  def ofString(s: String): Result[Location] = {
    def failure = Error("Error parsing location string: " + s)
    if(s.length != 2
      || s(0) <  'a'
      || s(0) >= ('a' + Board.SIZE)
      || s(1) < '1'
      || s(1) >= ('1' + Board.SIZE)
    )
      return failure

    val x = s(0) - 'a'
    val y = s(1) - '1'
    Ok(Location(x,y))
  }

  val values: List[Location] = (
    for(y <- 0 to 7; x <- 0 to 7)
    yield Location(x,y)
  )(collection.breakOut)
}
