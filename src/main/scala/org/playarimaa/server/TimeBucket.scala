package org.playarimaa.server

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.Utils._

object TimeBucket {
  //How many seconds to wait at minimum between cleanups to save memory
  val CLEANUP_DELAY : Double = 300.0
}

//Single bucket that refills at a certain rate, used for throttling, synchronized
class TimeBucket(val capacity: Double, val fillPerSec: Double) {
  private var curLevel: Double = capacity
  private var lastUpdateTime: Double = Timestamp.get

  private def update(now: Timestamp): Unit = {
    if(now > lastUpdateTime) {
      curLevel = math.min(capacity, curLevel + fillPerSec * (now - lastUpdateTime))
      lastUpdateTime = now
    }
  }

  def take(amount: Double, now: Timestamp): Boolean = this.synchronized {
    update(now)
    if(curLevel >= amount) {
      curLevel -= amount
      return true
    }
    return false
  }

  def put(amount: Double, now: Timestamp): Unit = this.synchronized {
    update(now)
    curLevel = math.min(capacity, curLevel + amount)
  }

  def takeOne(now: Timestamp): Boolean = {
    take(1.0,now)
  }
  def putOne(now: Timestamp): Unit = {
    put(1.0,now)
  }

  def isFull(now: Timestamp): Boolean = this.synchronized {
    update(now)
    return curLevel >= capacity
  }
}


//Multiple buckets indexed by a key, synchronized
class TimeBuckets[T](val capacity: Double, val fillPerSec: Double) {

  private var buckets: Map[T,TimeBucket] = Map()
  private var lastCleanupTime = Timestamp.get

  //Clean up old buckets that are full to conserve memory
  private def maybeCleanup(now: Timestamp): Unit = {
    if(now > lastCleanupTime + TimeBucket.CLEANUP_DELAY) {
      buckets = buckets.filter { case (_,bucket) => !bucket.isFull(now) }
      lastCleanupTime = now
    }
  }

  def take(key: T, amount: Double, now: Timestamp): Boolean = this.synchronized {
    maybeCleanup(now)
    val bucket =
      buckets.get(key) match {
        case None =>
          val newBucket = new TimeBucket(capacity, fillPerSec)
          buckets = buckets + (key -> newBucket)
          newBucket
        case Some(x) => x
      }
    bucket.take(amount,now)
  }

  def put(key: T, amount: Double, now: Timestamp): Unit = this.synchronized {
    maybeCleanup(now)
    buckets.get(key) match {
      case None => ()
      case Some(bucket) => 
        bucket.put(amount,now)
    }
  }

  def takeOne(key: T, now: Timestamp): Boolean = {
    take(key,1.0,now)
  }
  def putOne(key: T, now: Timestamp): Unit = {
    put(key,1.0,now)
  }
}


