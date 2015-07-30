package org.playarimaa.server.game

object TimeControl {
  /* Increment and delay are multiplied by this every turn during overtime
   * If changing this, make sure to update API docs. */
  val OVERTIME_FACTOR_PER_TURN: Double = 1.0 - 1.0 / 30.0
}

case class TimeControl(
  initialTime: Int,
  increment: Int,
  delay: Int,
  maxReserve: Int,
  maxMoveTime: Int,
  overtimeAfter: Int
)
{
  /* Compute the amount of time left on player clock after a player's turn */
  def timeLeftAfter(timeAtStartOfTurn: Double, timeUsed: Double, turn: Int): Double = {
    val overtimeFactor =
      if(turn >= overtimeAfter)
        math.pow(TimeControl.OVERTIME_FACTOR_PER_TURN, turn - overtimeAfter + 1)
      else
        1.0
    val adjIncrement = increment * overtimeFactor
    val adjDelay = delay * overtimeFactor

    math.min(maxReserve, timeAtStartOfTurn + adjIncrement - math.max(timeUsed - adjDelay, 0.0))
  }

  /* Compute the amount of time left on player clock given the player's whole history
   * of time usage on all the moves of the game. */
  def timeLeftFromHistory(timeUsageHistory: List[Double]): Double = {
    var timeLeft: Double = initialTime
    timeUsageHistory.zipWithIndex.foreach { case (timeUsed,turn) =>
      timeLeft = timeLeftAfter(timeLeft,timeUsed,turn)
    }
    timeLeft
  }
}
