package org.playarimaa.server.game
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.board.Player

case class GameResult(
  winner: Option[Player],
  reason: EndingReason,
  endTime: Timestamp
)
