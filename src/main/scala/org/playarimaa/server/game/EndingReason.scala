package org.playarimaa.server.game
import scala.util.{Try, Success, Failure}

sealed trait EndingReason {
  val char: Char

  override def toString: String =
    "" + char
}

object EndingReason {

  def ofChar(c: Char): Try[EndingReason] =
    c match {
      case 'g' => Success(GOAL)
      case 'e' => Success(ELIMINATION)
      case 'm' => Success(IMMOBILIZATION)
      case 't' => Success(TIME)
      case 'r' => Success(RESIGNATION)
      case 'i' => Success(ILLEGAL_MOVE)
      case 'a' => Success(ADJOURNED)
      case _   =>
        if(c >= 'a' && c <= 'z')
          Success(OTHER(c))
        else
          Failure(new IllegalArgumentException("Unknown ending reason: " + c))
    }

  def ofString(s: String): Try[EndingReason] = {
    if(s.length == 1)
      ofChar(s(0))
    else
      Failure(new IllegalArgumentException("Unknown player: " + s))
  }

  case object GOAL           extends EndingReason {val char = 'g'}
  case object ELIMINATION    extends EndingReason {val char = 'e'}
  case object IMMOBILIZATION extends EndingReason {val char = 'm'}
  case object TIME           extends EndingReason {val char = 't'}
  case object RESIGNATION    extends EndingReason {val char = 'r'}
  case object ILLEGAL_MOVE   extends EndingReason {val char = 'i'}
  case object ADJOURNED      extends EndingReason {val char = 'a'}
  case class OTHER(char: Char) extends EndingReason
}
