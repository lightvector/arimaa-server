package org.playarimaa.server
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.RandGen.Auth
import org.playarimaa.server.Accounts.Import._

class LoginTracker(inactivityTimeout: Double) {
  class LoginData() {
    //All auth keys this user has, along with the time they were most recently used
    var auths: Map[Auth,Timestamp] = Map()
    //Most recent time anything happened for this user
    var lastActive: Timestamp = Timestamp.get
  }

  private var loginData: Map[Username,LoginData] = Map()
  private var lastActive: Timestamp = Timestamp.get

  def findOrAddLogin(username: Username): LoginData = synchronized {
    val ld = loginData.getOrElse(username, new LoginData)
    loginData = loginData + (username -> ld)
    ld
  }

  def login(username: Username, now: Timestamp): Auth = synchronized {
    val auth = RandGen.genAuth
    val ld = findOrAddLogin(username)
    ld.auths = ld.auths + (auth -> now)
    ld.lastActive = now
    lastActive = now
    auth
  }

  /* Returns the LoginData for a user if that user is logged in */
  def getLoginData(username: Username, auth: Auth, now: Timestamp): Option[LoginData] = synchronized {
    loginData.get(username).flatMap { ld =>
      ld.auths.get(auth).flatMap { time =>
        if(now >= time + inactivityTimeout)
          None
        else {
          Some(ld)
        }
      }
    }
  }

  def isLoggedIn(username: Username, auth: Auth, now: Timestamp): Boolean = synchronized {
    getLoginData(username,auth,now).nonEmpty
  }

  def requireLogin(username: Username, auth: Auth, now: Timestamp): Boolean = synchronized {
    getLoginData(username,auth,now).map { ld =>
      ld.auths = ld.auths + (auth -> now)
      ld.lastActive = now
      lastActive = now
      true
    }.getOrElse(false)
  }

  def logout(username: Username, auth: Auth, now: Timestamp): Unit = synchronized {
    getLoginData(username,auth,now).foreach { ld =>
      ld.auths = ld.auths - auth
    }
  }

  def setLastActive(now: Timestamp) = synchronized {
    lastActive = now
  }

  //TODO cleanup old users periodically?
}
