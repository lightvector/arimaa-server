package org.playarimaa.server
import org.playarimaa.server.CommonTypes._

case class SimpleUserInfo (
  name: Username,
  rating: Double,
  isBot: Boolean,
  isGuest: Boolean
) extends Ordered[SimpleUserInfo]
{
  import scala.math.Ordered.orderingToOrdered

  def compare(that: SimpleUserInfo): Int =
    (this.name, this.rating, this.isBot, this.isGuest).compare((that.name, that.rating, that.isBot, that.isGuest))
}

case object SimpleUserInfo {
  val blank : SimpleUserInfo = new SimpleUserInfo("",0,false,false)
}
