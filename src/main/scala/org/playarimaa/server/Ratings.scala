package org.playarimaa.server
import org.playarimaa.server.CommonTypes._

object Ratings {

  type Rating = Double
  type Stdev = Double

  /* Returns (rw,rws),(rl,rls) where rw and rl are the new ratings of the winner
   * and loser respectively and rws and rls are the standard deviations that measure
   * the uncertainty in those ratings. */
  def newRatings(rWinner:(Rating,Stdev), rLoser:(Rating,Stdev)) : ((Rating,Stdev),(Rating,Stdev)) = {
    var rw = rWinner._1
    var rws = rWinner._2
    var rl = rLoser._1
    var rls = rLoser._2
    //TODO stupid current implementation just as dummy testing. Do something non-stupid here
    ((rw+rws,rws*0.9),(rl-rls,rls*0.9))
  }
}
