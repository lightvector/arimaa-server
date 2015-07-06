package org.playarimaa.server

object Timestamp {

  /** A timestamp to be recorded in a log or returned by the server in a query.
    * In particular, a number of seconds since the epoch (midnight Jan 1 1970) but
    * in a floating point format to allow sub-second precision.
    */
  type Timestamp = Double

  /** Returns a timestamp representing the current system time, with at least millisecond
    * precision. */
  def get: Timestamp = {
    System.currentTimeMillis.toDouble / 1000.0
  }
}
