package org.playarimaa.server

import java.security.SecureRandom

object AuthTokenGen {
  val NUM_SEED_BYTES = 32
  val NUM_TOKEN_INTS = 3

  private val secureRand: SecureRandom = new SecureRandom()
  private var initialized = false

  //This works around Scala being lazy by default - if we write this instead at toplevel 'static'
  //scope, it will get deferred until genToken is called the first time, and initialization
  //may actually take a little time.
  def initialize: Unit = {
    this.synchronized {
      secureRand.setSeed(SecureRandom.getSeed(NUM_SEED_BYTES))
      initialized = true
    }
  }

  def genToken: String = {
    this.synchronized {
      if(!initialized)
        throw new IllegalStateException("AuthTokenGen.initialize not called")

      List.range(0,NUM_TOKEN_INTS).
        map(_ => secureRand.nextInt).
        map(_.toHexString).
        mkString("")
    }
  }
}
