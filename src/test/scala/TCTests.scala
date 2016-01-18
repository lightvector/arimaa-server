import collection.mutable.Stack
import scala.util.{Try, Success, Failure}
import org.scalatest._

import org.playarimaa.server.game.TimeControl

class TCTests extends FlatSpec with Matchers {

  def approxEqual(x:Double, y:Double): Boolean =
    math.abs(x-y) <= 0.00001

  "TimeControl" should "take into account increment" in {
    {
      val tc = TimeControl(initialTime=100,increment=10,delay=0,None,None,None)
      approxEqual( 60, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 55, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 45, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual(-10, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
      approxEqual( 60, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 55, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 45, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual(-10, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
    }
  }

  it should "take into account delay" in {
    {
      val tc = TimeControl(initialTime=100,increment=0,delay=10,None,None,None)
      approxEqual( 50, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 50, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 45, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual(-10, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
      approxEqual( 60, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 55, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 45, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual(-10, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
    }
  }

  it should "take into account both together" in {
    {
      val tc = TimeControl(initialTime=100,increment=15,delay=10,None,None,None)
      approxEqual( 65, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 65, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 60, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual( 40, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 35, plyNum = 10)) should be (true)
      approxEqual(  5, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
      approxEqual( 75, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 70, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 60, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual( 40, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 35, plyNum = 10)) should be (true)
      approxEqual(  5, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
    }
  }

  it should "cap by max reserve" in {
    {
      val tc = TimeControl(initialTime=100,increment=15,delay=10,maxReserve=Some(62),None,None)
      approxEqual( 62, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 62, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 60, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual( 40, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 35, plyNum = 10)) should be (true)
      approxEqual(  5, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
      approxEqual( 75, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 70, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 60, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual( 40, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 35, plyNum = 10)) should be (true)
      approxEqual(  5, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
    }
  }

  it should "cap by max move time" in {
    {
      val tc = TimeControl(initialTime=100,increment=15,delay=10,maxReserve=Some(62),maxMoveTime=Some(60),None)
      approxEqual( 62, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 62, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 60, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual( 40, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 35, plyNum = 10)) should be (true)
      approxEqual(  5, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
      approxEqual( 60, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  0, plyNum = 10)) should be (true)
      approxEqual( 55, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  5, plyNum = 10)) should be (true)
      approxEqual( 45, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 15, plyNum = 10)) should be (true)
      approxEqual( 25, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 35, plyNum = 10)) should be (true)
      approxEqual(-10, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent = 70, plyNum = 10)) should be (true)
    }
  }

  it should "apply overtime" in {
    {
      val tc = TimeControl(initialTime=100,increment=20,delay=10,None,None,overtimeAfter=Some(40))
      approxEqual(40       , tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 79)) should be (true)
      approxEqual(40       , tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 80)) should be (true)
      approxEqual(40       , tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 81)) should be (true)
      approxEqual(39       , tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 82)) should be (true)
      approxEqual(39       , tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 83)) should be (true)
      approxEqual(38.033333, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 84)) should be (true)
      approxEqual(38.033333, tc.clockAfterTurn(clockBeforeTurn = 50, timeSpent =  40, plyNum = 85)) should be (true)
      approxEqual(40       , tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 79)) should be (true)
      approxEqual(40       , tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 80)) should be (true)
      approxEqual(40       , tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 81)) should be (true)
      approxEqual(39       , tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 82)) should be (true)
      approxEqual(39       , tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 83)) should be (true)
      approxEqual(38.033333, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 84)) should be (true)
      approxEqual(38.033333, tc.timeLeftUntilLoss(clockBeforeTurn = 50, timeSpent =  40, plyNum = 85)) should be (true)
    }
  }

  it should "have the right total time" in {
    {
      val tc = TimeControl(initialTime=100,increment=30,delay=0,None,None,overtimeAfter=Some(70))
      approxEqual( 100, tc.clockFromHistory(List.fill(0) { 0.0 })) should be (true)
      approxEqual(2200, tc.clockFromHistory(List.fill(70) { 0.0 })) should be (true)
      approxEqual(3100, tc.clockFromHistory(List.fill(1000) { 0.0 })) should be (true)
    }
  }

}
