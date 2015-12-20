package org.playarimaa.server
import scala.math
import breeze.linalg._
import org.playarimaa.server.CommonTypes._

case class Rating(
  val mean: Double,
  val stdev: Double //standard deviation measuring uncertainty of belief about rating
) extends Ordered[Rating] {
  def *(x: Double) : Rating = new Rating(mean*x,stdev*x)
  def /(x: Double) : Rating = new Rating(mean/x,stdev/x)

  import scala.math.Ordered.orderingToOrdered

  def compare(that: Rating): Int =
    (this.mean, this.stdev).compare((that.mean, that.stdev))
}

object Rating {

  //Initial rating value for new players
  val newPlayerPrior = new Rating(1500,500)
  def givenRatingPrior(rating: Double) = {
    //Clamp user-provided ratings to avoid silly numbers
    val mean = math.min(math.max(rating,1200),2400)
    val stdev = 150.0
    new Rating(mean,stdev)
  }

  /* Converts from Elo scale (400 points = 10x odds of winning) to nat scale (1 point = ex odds of winning)
   * where e is the base of the natural log. */
  val eloPerNat = 400.0 / math.log(10)
  def toNat(r: Rating) : Rating =
    r / eloPerNat
  def ofNat(r: Rating) : Rating =
    r * eloPerNat

  def square(x: Double) : Double = x * x

  /* Returns a tuple of the new ratings of the winner and loser. */
  def newRatings(rW:Rating, rL:Rating) : (Rating,Rating) = {
    val rWinner = toNat(rW)
    val rLoser = toNat(rL)

    val wPrecision = 1.0 / (rWinner.stdev * rWinner.stdev)
    val lPrecision = 1.0 / (rLoser.stdev * rLoser.stdev)

    //The negative log probability that someone diff points better would win
    def winError(diff: Double) = {
      if(diff <= -40.0) -diff //the below expression evaluates to -diff in the limit, this just prevents float blowup from exp
      else math.log(1.0 + math.exp(-diff))
    }
    def dWinErrordDiff(diff: Double) = {
      - 1.0 / (1.0 + math.exp(diff))
    }
    def d2WinErrordDiff2(diff: Double) = {
      1.0 / (2.0 + math.exp(diff) + math.exp(-diff))
    }

    //This is the negative log probability of a tuple of the player's ratings, given a prior on the players that their
    //ratings are gaussians with the given mean and stdev, given the game result.
    def error(mean: DenseVector[Double]) : Double = {
      0.5 * lPrecision * square(mean(0) - rLoser.mean) +
      0.5 * wPrecision * square(mean(1) - rWinner.mean) +
      winError(mean(1)-mean(0))
    }
    //Gradient of the error w.r.t means
    def dErrordMean(mean: DenseVector[Double]) : DenseVector[Double] = {
      val vec = DenseVector.zeros[Double](2)
      vec(0) :+= lPrecision * (mean(0)-rLoser.mean)
      vec(1) :+= wPrecision * (mean(1)-rWinner.mean)
      val x = dWinErrordDiff(mean(1)-mean(0))
      vec(0) :+= -x
      vec(1) :+= x
      vec
    }
    //Precision matrix of the error w.r.t means
    def d2ErrordMean2(mean: DenseVector[Double]) : DenseMatrix[Double] = {
      val mat = DenseMatrix.zeros[Double](2,2)
      mat(0,0) :+= lPrecision
      mat(1,1) :+= wPrecision
      val x = d2WinErrordDiff2(mean(1)-mean(0))
      mat(0,0) :+= x
      mat(0,1) :+= -x
      mat(1,0) :+= -x
      mat(1,1) :+= x
      mat
    }

    //Initial assignment
    val mean: DenseVector[Double] = DenseVector(rWinner.mean,rLoser.mean)

    //Perform a local newton step, but scaled so that we definitely improve error
    def newtonStep() : Unit = {
      val initialError = error(mean)
      val grad = dErrordMean(mean)
      val prec = d2ErrordMean2(mean)
      //Solve prec * x = grad, and negate to get the step we should take
      val x = - (prec \ grad)

      def errorOf(factor: Double): Double =
        error(mean + x * factor)

      //Repeatedly halve until we're happy with the newton step
      def findGoodFactor() : Double = {
        var factor = 1.0
        for(i <- 1 to 30) {
          if(errorOf(factor) < initialError)
            return factor
          factor /= 2.0
        }
        return factor
      }
      var factor = findGoodFactor()
      if(errorOf(factor / 2.0) < errorOf(factor))
        factor /= 2.0
      mean :+= mean + x * factor
      ()
    }

    //TODO do something smarter?
    //Perform a few newton steps
    for(i <- 1 to 10)
      newtonStep()

    //Now compute the final variances
    val prec = d2ErrordMean2(mean)
    val variance = inv(prec)

    //Here we go!
    val newLoser = Rating(mean(0),math.sqrt(variance(0,0)))
    val newWinner = Rating(mean(1),math.sqrt(variance(1,1)))

    (ofNat(newWinner),ofNat(newLoser))
  }
}
