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

  private def findOrAddLogin(username: Username): LoginData = synchronized {
    val ld = loginData.getOrElse(username, new LoginData)
    loginData = loginData + (username -> ld)
    ld
  }

  /* Returns the LoginData for a user if that user is logged in */
  private def getLoginData(username: Username, auth: Auth): Option[LoginData] = synchronized {
    loginData.get(username).flatMap { ld =>
      if(ld.auths.contains(auth))
        Some(ld)
      else
        None
    }
  }

  /* Log a user in */
  def login(username: Username, now: Timestamp): Auth = synchronized {
    val auth = RandGen.genAuth
    val ld = findOrAddLogin(username)
    ld.auths = ld.auths + (auth -> now)
    ld.lastActive = now
    lastActive = now
    auth
  }

  /* Returns true if the user is logged with a given auth */
  def isLoggedIn(username: Username, auth: Auth): Boolean = synchronized {
    getLoginData(username,auth).nonEmpty
  }
  def isUserLoggedIn(username: Username): Boolean = synchronized {
    loginData.get(username).nonEmpty
  }
  /* Returns true if anyone is logged in */
  def isAnyoneLoggedIn: Boolean = synchronized {
    loginData.nonEmpty
  }
  /* Returns the last time that any activity occurred */
  def lastActiveTime: Timestamp = synchronized {
    lastActive
  }

  def usersLoggedIn: Set[Username] = synchronized {
    loginData.keySet
  }

  /* Same as [isLoggedIn], but also updates a user's last active time for
   * timeout-checking purposes */
  def heartbeat(username: Username, auth: Auth, now: Timestamp): Boolean = synchronized {
    getLoginData(username,auth).map { ld =>
      ld.auths = ld.auths + (auth -> now)
      ld.lastActive = now
      lastActive = now
      true
    }.getOrElse(false)
  }

  /* Log a user out */
  def logout(username: Username, auth: Auth, now: Timestamp): Unit = synchronized {
    getLoginData(username,auth).foreach { ld =>
      ld.auths = ld.auths - auth
    }
    lastActive = now
  }

  /* Log all of a user's auths out */
  def logoutUser(username: Username, now: Timestamp): Unit = synchronized {
    loginData = loginData - username
    lastActive = now
  }

  /* Log out all users or auths that have not been active recently enough.
   * Returns all users logged out. */
  def doTimeouts(now: Timestamp): List[Username] = synchronized {
    var timedOut: List[Username] = List()
    loginData = loginData.filter { case (username,ld) =>
      if(now >= ld.lastActive + inactivityTimeout) {
        timedOut = username :: timedOut
        false
      }
      else {
        ld.auths = ld.auths.filter { case (_,time) =>
          !(now >= time + inactivityTimeout)
        }
        ld.auths.nonEmpty
      }
    }
    timedOut
  }

}
