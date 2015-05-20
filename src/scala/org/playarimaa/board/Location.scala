package org.playarimaa.board
import org.playarimaa.util._

case class Location (x: Int, y: Int) {

  def isOffBoard: Boolean =
    x < 0 || x > 7 || y < 0 || y > 7
  def isOnBoard: Boolean =
    !this.isOffBoard

  override def toString: String =
    if(this.isOffBoard)
      "OOB" + super.toString
    else
      "" + ('a' + this.x).toChar + this.y

  def apply(dir: Direction) =
    Location(x + dir.offset._1, y + dir.offset._2)
}

object Location {

  def ofString(s: String): Result[Location] = {
    def failure = Error("Error parsing location string: " + s)
    if(s.length != 2
      || s(0) < 'a'
      || s(0) > 'h'
      || s(1) < '1'
      || s(1) > '8')
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
