package org.playarimaa.server

import scala.io.Source
import scala.io.Codec
import java.security.SecureRandom
import java.io.IOException
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server.CommonTypes._

object RandGen {
  val NUM_SEED_BYTES = 32
  val NUM_AUTH_INTS = 3
  val NUM_GAME_ID_INTS = 2
  val NUM_USER_ID_INTS = 2

  private val secureRand: SecureRandom = new SecureRandom()
  private var initialized = false

  private val logger =  LoggerFactory.getLogger(getClass)

  //This works around Scala being lazy by default - if we write this instead at toplevel 'static'
  //scope, it will get deferred until genToken is called the first time, and initialization
  //may actually take a little time.
  def initialize(): Unit = {
    this.synchronized {
      if(!initialized) {
        try {
          //Try /dev/urandom since the default seeding uses /dev/random and is very slow to start
          val buf = Source.fromFile("/dev/urandom")(Codec.ISO8859)
          val bytes = buf.take(NUM_SEED_BYTES * 2).map(_.toByte).toArray
          if(bytes.length < NUM_SEED_BYTES * 2)
            throw new IOException("Not all desired bytes read")
          secureRand.setSeed(bytes)
        }
        catch
        {
          case e: Exception => {
            logger.error("Error initializing from /dev/urandom, using default seed initialization: " +  e)
            secureRand.setSeed(SecureRandom.getSeed(NUM_SEED_BYTES))
          }
        }
        initialized = true
      }
    }
  }

  def genToken(numInts: Int): String = {
    this.synchronized {
      if(!initialized)
        throw new IllegalStateException("RandGen.initialize not called")

      List.range(0,numInts).
        map(_ => secureRand.nextInt).
        map("%08x".format(_)).
        mkString("")
    }
  }

  def genAuth: Auth =
    genToken(NUM_AUTH_INTS)
  def genGameID: GameID =
    genToken(NUM_GAME_ID_INTS)
  def genUserID: UserID =
    genToken(NUM_USER_ID_INTS)

  def genBoolean: Boolean = this.synchronized {
    if(!initialized)
      throw new IllegalStateException("RandGen.initialize not called")
    secureRand.nextBoolean
  }


  def main(args: Array[String]): Unit = {
    if(args.length != 2)
      throw new Exception("Args: numInts quantity")
    initialize()
    val numInts = args(0).toInt
    val qty = args(1).toInt
    for(i <- 1 to qty)
      println(genToken(numInts))
  }
}
