package org.playarimaa.server
import org.playarimaa.server.CommonTypes._

case class SimpleUserInfo (
  name: Username,
  rating: Rating,
  isBot: Boolean,
  isGuest: Boolean
) extends Ordered[SimpleUserInfo]
{
  import scala.math.Ordered.orderingToOrdered

  def compare(that: SimpleUserInfo): Int =
    (this.name, this.rating, this.isBot, this.isGuest).compare((that.name, that.rating, that.isBot, that.isGuest))
}

case object SimpleUserInfo {
  val blank : SimpleUserInfo = new SimpleUserInfo("",Rating(0,0),false,false)

  def ofDB(x: (String,Double,Double,Boolean,Boolean)) : SimpleUserInfo = {
    x match { case (name, rating, ratingStdev, isBot, isGuest) =>
      SimpleUserInfo(name, Rating(rating, ratingStdev), isBot, isGuest)
    }
  }

  def toDB(x: SimpleUserInfo) : (String,Double,Double,Boolean,Boolean) = {
    (x.name, x.rating.mean, x.rating.stdev, x.isBot, x.isGuest)
  }
}
