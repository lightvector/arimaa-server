import collection.mutable.Stack
import scala.util.{Try, Success, Failure}
import org.scalatest._

import org.playarimaa.server.Rating

class RatingTests extends FlatSpec with Matchers {

  def approxEqual(x:Double, y:Double, tol:Double): Boolean =
    math.abs(x-y) <= tol

  "Rating" should "produce expected values after simple game results" in {
    {
      val (rW,rL) = Rating.newRatings(Rating(1500,150), Rating(1500,150))
      approxEqual(rW.mean,1547,1) should be (true)
      approxEqual(rW.stdev,140,1) should be (true)
      approxEqual(rL.mean,1453,1) should be (true)
      approxEqual(rL.stdev,140,1) should be (true)
    }
    {
      val (rW,rL) = Rating.newRatings(Rating(1500,150), Rating(1500,400))
      println(rW + " " + rL)
      approxEqual(rW.mean,1528,1) should be (true)
      approxEqual(rW.stdev,145,1) should be (true)
      approxEqual(rL.mean,1302,1) should be (true)
      approxEqual(rL.stdev,299,1) should be (true)
    }
  }
}
