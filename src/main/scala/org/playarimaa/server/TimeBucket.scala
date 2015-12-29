package org.playarimaa.server

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._


class TimeBucket(val capacity: Double, val fillPerSec: Double) {
  private var curLevel: Double = capacity
  private var lastUpdateTime: Double = Timestamp.get

  private def update(now: Timestamp): Unit = {
    if(now > lastUpdateTime) {
      curLevel = math.min(capacity, curLevel + fillPerSec * (now - lastUpdateTime))
      lastUpdateTime = now
    }
  }

  def takeOne(now: Timestamp): Boolean = {
    update(now)
    if(curLevel >= 1.0) {
      curLevel -= 1.0
      return true
    }
    return false
  }

  def isFull(now: Timestamp): Boolean = {
    update(now)
    return curLevel >= capacity
  }
}





