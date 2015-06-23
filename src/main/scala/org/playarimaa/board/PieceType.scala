package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait PieceType extends Ordered[PieceType] {
  val strength: Int
  val lowercaseChar: Char

  def compare(that: PieceType): Int =
    this.strength.compare(that.strength)
}

object PieceType {
  val values = List(RAB,CAT,DOG,HOR,CAM,ELE)

  def ofChar(c: Char): Try[PieceType] =
    c.toLower match {
      case 'r' => Success(RAB)
      case 'c' => Success(CAT)
      case 'd' => Success(DOG)
      case 'h' => Success(HOR)
      case 'm' => Success(CAM)
      case 'e' => Success(ELE)
      case _   => Failure(new IllegalArgumentException("Unknown piece type: " + c))
    }
}
case object RAB extends PieceType {val strength = 1; val lowercaseChar = 'r'}
case object CAT extends PieceType {val strength = 2; val lowercaseChar = 'c'}
case object DOG extends PieceType {val strength = 3; val lowercaseChar = 'd'}
case object HOR extends PieceType {val strength = 4; val lowercaseChar = 'h'}
case object CAM extends PieceType {val strength = 5; val lowercaseChar = 'm'}
case object ELE extends PieceType {val strength = 6; val lowercaseChar = 'e'}
