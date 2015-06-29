package org.playarimaa.board
import scala.util.{Try, Success, Failure}

case class Piece(owner: Player, pieceType: PieceType) {
  def toChar: Char =
    this.owner match {
      case GOLD => this.pieceType.lowercaseChar.toUpper
      case SILV => this.pieceType.lowercaseChar
    }

  override def toString: String =
    toChar.toString
}

object Piece {

  val values: List[Piece] =
    Player.values.flatMap( p =>
      PieceType.values.map( pt =>
        Piece(p,pt)
      )
    )

  def ofChar(c: Char): Try[Piece] =
    PieceType.ofChar(c).map { pt =>
      val player = if(c.isUpper) GOLD else SILV
      Piece(player,pt)
    }

  /** For testing only. */
  def main(args: Array[String]) {
    Piece.values foreach ((s: Piece) => println(s.toChar))
  }
}
