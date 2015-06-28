package org.playarimaa.server

object Timestamp {
  type Timestamp = Double

  def get: Timestamp = {
    System.currentTimeMillis.toDouble / 1000.0
  }
}
