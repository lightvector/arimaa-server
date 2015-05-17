package com.dreamsmadevisible.arimaa.server

object Piece extends Enumeration {

  case class PieceVal(strength: Int, abbreviation: Char) extends Val

  val RABBIT_GOLD = PieceVal(1, 'R')
  val CAT_GOLD = PieceVal(2, 'C')
  val DOG_GOLD = PieceVal(3, 'D')
  val HORSE_GOLD = PieceVal(4, 'H')
  val CAMEL_GOLD = PieceVal(5, 'M')
  val ELEPHANT_GOLD = PieceVal(6, 'E')

  val RABBIT_SILVER = PieceVal(1, 'r')
  val CAT_SILVER = PieceVal(2, 'c')
  val DOG_SILVER = PieceVal(3, 'd')
  val HORSE_SILVER = PieceVal(4, 'h')
  val CAMEL_SILVER = PieceVal(5, 'm')
  val ELEPHANT_SILVER = PieceVal(6, 'e')

  /** For testing only. */
  def main(args: Array[String]) {
    Piece.values foreach (s => println(s.asInstanceOf[PieceVal].abbreviation))
  }
}
