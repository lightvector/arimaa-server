package org.playarimaa.server

case class LogInfo(
  val remoteHost: String
) {

  override def toString: String = {
    "("+remoteHost+")"
  }
}


