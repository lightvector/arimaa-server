package org.playarimaa.server.game

case class TimeControl(
  initialTime: Int,
  increment: Int,
  delay: Int,
  maxReserve: Int,
  maxMoveTime: Int,
  overtimeAfter: Int
)
