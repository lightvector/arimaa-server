package org.playarimaa.board
import scala.util.{Try, Success, Failure}

sealed trait GameType {
  val string: String

  override def toString: String =
    string

  //Whether to allow a setup containing fewer than the normal 16 pieces
  def allowPartialSetup: Boolean =
    this match {
      case GameType.STANDARD => false
      case GameType.HANDICAP => true
      case GameType.DIRECTSETUP => true
    }

  //Whether to allow setting up pieces on the whole board rather than just home rows
  def allowWholeBoardSetup: Boolean =
    this match {
      case GameType.STANDARD => false
      case GameType.HANDICAP => false
      case GameType.DIRECTSETUP => true
    }
}

object GameType {

  def ofString(s: String): Try[GameType] =
    values.find(_.string == s) match {
      case None => Failure(new IllegalArgumentException("Unsupported game type: " + s))
      case Some(gt) => Success(gt)
    }

  case object STANDARD    extends GameType {val string = "standard"}
  case object HANDICAP    extends GameType {val string = "handicap"}
  case object DIRECTSETUP extends GameType {val string = "directsetup"}

  val values = List(
    STANDARD,
    HANDICAP,
    DIRECTSETUP
  )
}
