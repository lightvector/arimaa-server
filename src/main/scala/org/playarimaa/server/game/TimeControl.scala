package org.playarimaa.server.game
import org.playarimaa.server.Utils._

object TimeControl {
  /* Increment and delay are multiplied by this every turn during overtime
   * If changing this, make sure to update API docs. */
  val OVERTIME_FACTOR_PER_TURN: Double = 1.0 - 1.0 / 30.0

  val POSTAL_RESERVE_THRESHOLD: Double = 86400 * 2 //2 days
  val POSTAL_PERMOVE_THRESHOLD: Double = 3600 * 2 //2 hours
}

case class TimeControl(
  initialTime: Double,
  increment: Double,
  delay: Double,
  maxReserve: Option[Double],
  maxMoveTime: Option[Double],
  overtimeAfter: Option[Int]
)
{
  /* Raise an exception if this time control is nonsensical */
  def validate() : Unit = {
    initialTime.validateNonNegative("initialTime")
    increment.validateNonNegative("increment")
    delay.validateNonNegative("delay")
    maxReserve.foreach(_.validateNonNegative("maxReserve"))
    maxMoveTime.foreach(_.validateNonNegative("maxMoveTime"))
    overtimeAfter.foreach(_.validateNonNegative("overtimeAfter"))

    if(maxReserve.exists(_ < initialTime))
      throw new Exception("Invalid time control: maxReserve < initialTime")

    //Exclude some obviously unreasonable time controls
    val startingTime = initialTime + increment + delay
    if(startingTime < 10 || maxMoveTime.exists(_ < 10))
      throw new Exception("Invalid time control: too fast")
  }

  /* Compute the amount of time left on player clock after a player's turn */
  def clockAfterTurn(clockBeforeTurn: Double, timeSpent: Double, turn: Int): Double = {
    val overtimeFactor =
      if(overtimeAfter.exists(turn >= _))
        math.pow(TimeControl.OVERTIME_FACTOR_PER_TURN, turn - overtimeAfter.get + 1.0)
      else
        1.0
    val adjIncrement = increment * overtimeFactor
    val adjDelay = delay * overtimeFactor
    var clock = clockBeforeTurn + adjIncrement - math.max(timeSpent - adjDelay, 0.0)
    maxReserve.foreach { maxReserve => clock = math.min(clock, maxReserve) }
    clock
  }

  /* Compute the amount of time left on player clock given the player's whole history
   * of time usage on all the moves of the game. */
  def clockFromHistory(timeUsageHistory: Seq[Double]): Double = {
    var clock: Double = initialTime
    timeUsageHistory.zipWithIndex.foreach { case (timeSpent,turn) =>
      clock = clockAfterTurn(clock,timeSpent,turn)
    }
    clock
  }

  /* Compute the remaining amount of time a player can use this turn before losing due to time */
  def timeLeftUntilLoss(clockBeforeTurn: Double, timeSpent: Double, turn: Int): Double = {
    var clock = clockAfterTurn(clockBeforeTurn, timeSpent, turn)
    maxMoveTime match {
      case None => clock
      case Some(maxMoveTime) => math.min(clock, maxMoveTime - timeSpent)
    }
  }

  /* Determine whether a player has run out of time given the time on their clock and the amount
   * of time spent on a given move so far */
  def isOutOfTime(clock: Double, timeSpent: Double): Boolean = {
    clock < 0.0 || maxMoveTime.exists(timeSpent > _)
  }

  /* Determine whether this time control should be considered a postal time control */
  def isPostal: Boolean = {
    math.min(initialTime, maxReserve.getOrElse(initialTime)) >= TimeControl.POSTAL_RESERVE_THRESHOLD ||
    math.min(increment + delay, maxMoveTime.getOrElse(increment+delay)) >= TimeControl.POSTAL_PERMOVE_THRESHOLD
  }
}
