package org.playarimaa.server
import org.playarimaa.server.CommonTypes._

case class SimpleUserInfo (
  name: Username,
  rating: Double,
  isBot: Boolean
) extends Ordered[SimpleUserInfo]
{
  import scala.math.Ordered.orderingToOrdered

  def compare(that: SimpleUserInfo): Int =
    (this.name, this.rating, this.isBot).compare((that.name, that.rating, that.isBot))
}

case object SimpleUserInfo {
  val blank : SimpleUserInfo = new SimpleUserInfo("",0,false)
}
