package org.playarimaa.server

object Mode {

  //Is the server running in production mode?
  def isProd: Boolean = {
    System.getProperty("isProd") match {
      case "true" => true
      case "false" => false
      case null => throw new Exception("isProd system property undefined, expected \"true\" or \"false\"")
      case x => throw new Exception("Unexpected value for isProd system property, expected \"true\" or \"false\": " + x)
    }
  }
}
