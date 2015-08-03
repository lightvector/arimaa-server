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
  maxReserve: Option[Int],
  maxMoveTime: Option[Int],
  overtimeAfter: Option[Int]
)
{
  /* Compute the amount of time left on player clock after a player's turn */
  def timeLeftAfterMove(timeAtStartOfTurn: Double, timeSpent: Double, turn: Int): Double = {
    val overtimeFactor =
      if(overtimeAfter.exists(turn >= _))
        math.pow(TimeControl.OVERTIME_FACTOR_PER_TURN, turn - overtimeAfter.get + 1)
      else
        1.0
    val adjIncrement = increment * overtimeFactor
    val adjDelay = delay * overtimeFactor
    var timeLeft = timeAtStartOfTurn + adjIncrement - math.max(timeSpent - adjDelay, 0.0)
    maxReserve.foreach { maxReserve => timeLeft = math.min(maxReserve, timeLeft) }
    timeLeft
  }

  /* Compute the amount of time left on player clock given the player's whole history
   * of time usage on all the moves of the game. */
  def timeLeftFromHistory(timeUsageHistory: Seq[Double]): Double = {
    var timeLeft: Double = initialTime
    timeUsageHistory.zipWithIndex.foreach { case (timeSpent,turn) =>
      timeLeft = timeLeftAfterMove(timeLeft,timeSpent,turn)
    }
    timeLeft
  }

  def timeLeftInCurrentTurn(timeAtStartOfTurn: Double, timeSpent: Double, turn: Int): Double = {
    var timeLeft = timeLeftAfterMove(timeAtStartOfTurn, timeSpent, turn)
    maxMoveTime.foreach { maxMoveTime => timeLeft = math.min(maxMoveTime - timeSpent, timeLeft) }
    timeLeft
  }

  def isOutOfTime(timeLeft: Double, timeSpent: Double): Boolean = {
    timeLeft < 0.0 || maxMoveTime.exists(timeSpent > _)
  }
}
