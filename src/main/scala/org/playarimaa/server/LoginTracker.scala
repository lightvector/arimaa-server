package org.playarimaa.server
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp

/* Implements a basic login system with support for timeouts.
 * [inactivityTimeout] specifies how old to require a login is to time it out
 * when [doTimeouts] is called.
 * [parent] specifies a parent login tracker such that if [parentAuth] is provided to
 * login, heartbeats will heartbeat the parent login as well, and doTimeouts will
 * check if the parent is logged in or not.
 */
class LoginTracker(val parent: Option[LoginTracker], val inactivityTimeout: Double) {
  class LoginData() {
    //All auth keys this user has, along with the time they were most recently used
    var auths: Map[Auth,Timestamp] = Map()
    //Most recent time anything happened for this user
    var lastActive: Timestamp = Timestamp.get
  }

  private var loginData: Map[Username,LoginData] = Map()
  private var lastActive: Timestamp = Timestamp.get

  private var userAndParentAuth: Map[Auth,(Username,Option[Auth])] = Map()

  private def findOrAddLoginData(username: Username): LoginData = synchronized {
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

  /* Log a user in. */
  def login(username: Username, now: Timestamp): Auth =
    login(username,now,None)
  def login(username: Username, now: Timestamp, parentAuth: Option[Auth]): Auth = {
    val auth = RandGen.genAuth
    if(userAndParentAuth.contains(auth))
      login(username,now,parentAuth)
    else synchronized {
      if(parentAuth.nonEmpty && parent.isEmpty)
        throw new IllegalArgumentException("parentAuth provided when parent is None")
      if(parentAuth.isEmpty && parent.nonEmpty)
        throw new IllegalArgumentException("parentAuth not provided when parent is Some")

      val ld = findOrAddLoginData(username)
      ld.auths = ld.auths + (auth -> now)
      userAndParentAuth = userAndParentAuth + (auth -> ((username,parentAuth)))
      ld.lastActive = now
      lastActive = now
      auth
    }
  }

  def isLoggedIn(username: Username, auth: Auth): Boolean = synchronized {
    getLoginData(username,auth).nonEmpty
  }
  def isUserLoggedIn(username: Username): Boolean = synchronized {
    loginData.get(username).nonEmpty
  }
  def isAuthLoggedIn(auth: Auth): Boolean = synchronized {
    userAndParentAuth.contains(auth)
  }
  def isAnyoneLoggedIn: Boolean = synchronized {
    loginData.nonEmpty
  }

  def userOfAuth(auth: Auth): Option[Username] = synchronized {
    userAndParentAuth.get(auth).map { case (username,_) => username }
  }

  /* Returns the last time that any activity occurred */
  def lastActiveTime: Timestamp = synchronized {
    lastActive
  }
  /* Returns all users logged in */
  def usersLoggedIn: Set[Username] = synchronized {
    loginData.keySet
  }

  /* Updates a user's last active time for timeout-checking purposes.
   * Returns None if the user was not logged in, and Some if the user was. */
  def heartbeat(username: Username, auth: Auth, now: Timestamp): Option[Username] = synchronized {
    lastActive = now
    getLoginData(username,auth).map { ld =>
      ld.auths = ld.auths + (auth -> now)
      ld.lastActive = now
      val (username,parentAuth) = userAndParentAuth(auth)
      parentAuth.foreach { parentAuth => parent.get.heartbeat(username, auth, now) }
      username
    }
  }

  /* Same as [heartbeat], but performs a lookup to find the username using only the auth */
  def heartbeatAuth(auth: Auth, now: Timestamp): Option[Username] = synchronized {
    lastActive = now
    userAndParentAuth.get(auth).flatMap { case (username,_) =>
      heartbeat(username, auth, now)
    }
  }

  /* Log a single auth of a user out */
  def logout(username: Username, auth: Auth, now: Timestamp): Unit = synchronized {
    lastActive = now
    getLoginData(username,auth).foreach { ld =>
      ld.auths = ld.auths - auth
    }
  }
  /* Log a single auth of a user out */
  def logoutAuth(auth: Auth, now: Timestamp): Unit = synchronized {
    lastActive = now
    userAndParentAuth.get(auth).foreach { case (username,_) =>
      logout(username, auth, now)
    }
  }
  /* Log all of a user's auths out */
  def logoutUser(username: Username, now: Timestamp): Unit = synchronized {
    lastActive = now
    loginData = loginData - username
  }

  /* Log out all users except the list specified */
  def logoutAllExcept(users: List[Username]): Unit = synchronized {
    loginData = loginData.filter { case (username,_) =>
      val shouldKeep = users.contains(username)
      shouldKeep
    }
  }

  /* Log out all auths that have not been active recently enough or whose parents were logged out.
   * Returns all users all of whose auths were logged out. */
  def doTimeouts(now: Timestamp): List[Username] = synchronized {
    var usersTimedOut: List[Username] = List()
    loginData = loginData.filter { case (username,ld) =>
      ld.auths = ld.auths.filter { case (auth,time) =>
        val shouldKeep = now < time + inactivityTimeout &&
          userAndParentAuth(auth)._2.forall { parentAuth => parent.get.isAuthLoggedIn(parentAuth) }
        if(!shouldKeep)
          userAndParentAuth = userAndParentAuth - auth
        shouldKeep
      }
      val shouldKeep = ld.auths.nonEmpty
      if(!shouldKeep)
        usersTimedOut = username :: usersTimedOut
      shouldKeep
    }
    usersTimedOut
  }

}
