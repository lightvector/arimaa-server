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
  class LoginData(var info: SimpleUserInfo) {
    //All auth keys this user has, along with the time they were most recently used
    var auths: Map[Auth,Timestamp] = Map()
    //Most recent time anything happened for this user
    var lastActive: Timestamp = Timestamp.get
  }

  private var loginData: Map[Username,LoginData] = Map()
  private var lastActive: Timestamp = Timestamp.get

  private var userAndParentAuth: Map[Auth,(SimpleUserInfo,Option[Auth])] = Map()

  private def findOrAddLoginData(user: SimpleUserInfo): LoginData = synchronized {
    val ld = loginData.getOrElse(user.name, new LoginData(user))
    loginData = loginData + (user.name -> ld)
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
  def login(user: SimpleUserInfo, now: Timestamp): Auth =
    login(user,now,None)
  def login(user: SimpleUserInfo, now: Timestamp, parentAuth: Option[Auth]): Auth = {
    val auth = RandGen.genAuth
    if(userAndParentAuth.contains(auth))
      login(user,now,parentAuth)
    else synchronized {
      if(parentAuth.nonEmpty && parent.isEmpty)
        throw new IllegalArgumentException("parentAuth provided when parent is None")
      if(parentAuth.isEmpty && parent.nonEmpty)
        throw new IllegalArgumentException("parentAuth not provided when parent is Some")

      val ld = findOrAddLoginData(user)
      ld.auths = ld.auths + (auth -> now)
      userAndParentAuth = userAndParentAuth + (auth -> ((user,parentAuth)))
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

  def userOfAuth(auth: Auth): Option[SimpleUserInfo] = synchronized {
    userAndParentAuth.get(auth).map { case (user,_) => user }
  }

  /* Returns the last time that any activity occurred */
  def lastActiveTime: Timestamp = synchronized {
    lastActive
  }
  /* Returns all users logged in */
  def usersLoggedIn: List[SimpleUserInfo] = synchronized {
    loginData.values.map(_.info).toList
  }

  /* Updates a user's last active time for timeout-checking purposes.
   * Returns None if the user was not logged in, and Some if the user was. */
  def heartbeat(username: Username, auth: Auth, now: Timestamp): Option[SimpleUserInfo] = synchronized {
    lastActive = now
    getLoginData(username,auth).map { ld =>
      ld.auths = ld.auths + (auth -> now)
      ld.lastActive = now
      val (user,parentAuth) = userAndParentAuth(auth)
      parentAuth.foreach { parentAuth => parent.get.heartbeat(username, auth, now) }
      user
    }
  }

  /* Same as [heartbeat], but performs a lookup to find the username using only the auth */
  def heartbeatAuth(auth: Auth, now: Timestamp): Option[SimpleUserInfo] = synchronized {
    lastActive = now
    userAndParentAuth.get(auth).flatMap { case (user,_) =>
      heartbeat(user.name, auth, now)
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
    userAndParentAuth.get(auth).foreach { case (user,_) =>
      logout(user.name, auth, now)
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
