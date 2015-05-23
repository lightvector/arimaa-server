package org.playarimaa.board
import org.playarimaa.util._

sealed trait PieceType extends Ordered[PieceType] {
  def strength: Int
  def lowercaseChar: Char
  def compare(that: PieceType): Int = this.strength.compare(that.strength)
}

object PieceType {
  val values = List(RAB,CAT,DOG,HOR,CAM,ELE)

  def ofChar(c: Char): Result[PieceType] =
    c.toLower match {
      case 'r' => Ok(RAB)
      case 'c' => Ok(CAT)
      case 'd' => Ok(DOG)
      case 'h' => Ok(HOR)
      case 'm' => Ok(CAM)
      case 'e' => Ok(ELE)
      case _   => Error("Unknown piece type: " + c)
    }
}
case object RAB extends PieceType {val strength = 1; val lowercaseChar = 'r'}
case object CAT extends PieceType {val strength = 2; val lowercaseChar = 'c'}
case object DOG extends PieceType {val strength = 3; val lowercaseChar = 'd'}
case object HOR extends PieceType {val strength = 4; val lowercaseChar = 'h'}
case object CAM extends PieceType {val strength = 5; val lowercaseChar = 'm'}
case object ELE extends PieceType {val strength = 6; val lowercaseChar = 'e'}
