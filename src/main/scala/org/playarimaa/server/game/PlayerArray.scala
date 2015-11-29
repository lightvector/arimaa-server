package org.playarimaa.server.game
import org.playarimaa.board.Player
import org.playarimaa.board.{GOLD,SILV}

case class PlayerArray[T](val gold: T, val silv: T) {
  def apply(player: Player): T = {
    player match {
      case GOLD => gold
      case SILV => silv
    }
  }

  def map[U](f: T => U): PlayerArray[U] =
    PlayerArray(f(gold),f(silv))

  def mapi[U](f: (Player,T) => U): PlayerArray[U] =
    PlayerArray(f(GOLD,gold),f(SILV,silv))

  def exists(f: T => Boolean): Boolean =
    f(gold) || f(silv)

  def forAll(f: T => Boolean): Boolean =
    f(gold) && f(silv)

  def values: List[T] =
    List(gold,silv)

  def contains(x: T): Boolean =
    gold == x || silv == x

  def findPlayer(f: T => Boolean): Option[Player] = {
    if(f(gold))
      Some(GOLD)
    else if(f(silv))
      Some(SILV)
    else
      None
  }

  def +(kv:(Player,T)): PlayerArray[T] = {
    kv._1 match {
      case GOLD => PlayerArray(kv._2,silv)
      case SILV => PlayerArray(gold,kv._2)
    }
  }
}
