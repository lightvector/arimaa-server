package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait Piece {
  val owner: Player
  val pieceType: PieceType

  def toChar: Char =
    this.owner match {
      case GOLD => this.pieceType.lowercaseChar.toUpper
      case SILV => this.pieceType.lowercaseChar
    }

  def canFreeze(other : Piece) : Boolean = {
    if (other == null) { return false; }
    this.owner != other.owner && this.pieceType.compare(other.pieceType) > 0
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

  def apply(p : Player, pt : PieceType) = {
    (p, pt) match {
      case (GOLD, RAB) => GOLD_RAB
      case (GOLD, CAT) => GOLD_CAT
      case (GOLD, DOG) => GOLD_DOG
      case (GOLD, HOR) => GOLD_HOR
      case (GOLD, CAM) => GOLD_CAM
      case (GOLD, ELE) => GOLD_ELE
      case (SILV, RAB) => SILV_RAB
      case (SILV, CAT) => SILV_CAT
      case (SILV, DOG) => SILV_DOG
      case (SILV, HOR) => SILV_HOR
      case (SILV, CAM) => SILV_CAM
      case (SILV, ELE) => SILV_ELE
    }
  }

  def apply(c: Char): Piece = {
    ofChar(c).get
  }

  def ofChar(c: Char): Try[Piece] =
    PieceType.ofChar(c).map { pt =>
      val player = if(c.isUpper) GOLD else SILV
      Piece(player,pt)
    }
}

case object GOLD_RAB extends Piece {val owner = GOLD; val pieceType = RAB}
case object GOLD_CAT extends Piece {val owner = GOLD; val pieceType = CAT}
case object GOLD_DOG extends Piece {val owner = GOLD; val pieceType = DOG}
case object GOLD_HOR extends Piece {val owner = GOLD; val pieceType = HOR}
case object GOLD_CAM extends Piece {val owner = GOLD; val pieceType = CAM}
case object GOLD_ELE extends Piece {val owner = GOLD; val pieceType = ELE}

case object SILV_RAB extends Piece {val owner = SILV; val pieceType = RAB}
case object SILV_CAT extends Piece {val owner = SILV; val pieceType = CAT}
case object SILV_DOG extends Piece {val owner = SILV; val pieceType = DOG}
case object SILV_HOR extends Piece {val owner = SILV; val pieceType = HOR}
case object SILV_CAM extends Piece {val owner = SILV; val pieceType = CAM}
case object SILV_ELE extends Piece {val owner = SILV; val pieceType = ELE}
