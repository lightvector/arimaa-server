package org.playarimaa.server
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp

/* A basic login system with support for timeouts.
 *
 * @param parent a "parent" login tracker such that if parentAuth is provided to
 * login, heartbeats will heartbeat the parent login as well, and doTimeouts will
 * check if the parent is logged in or not.
 *
 * @param inactivityTimeout how old to require a login is to time it out when [doTimeouts] is called.
 *
 * @param updateInfosFromParent if true, also update SimpleUserInfos if they change in the parent.
 */
class LoginTracker(val parent: Option[LoginTracker], val inactivityTimeout: Double, val updateInfosFromParent: Boolean) {
  class LoginData(var info: SimpleUserInfo) {
    //All auth keys this user has, along with the time they were most recently used
    var auths: Map[Auth,Timestamp] = Map()
    //Most recent time anything happened for this user
    var lastActive: Timestamp = Timestamp.get
  }

  //We use UserID (i.e. lowercaseName) internally for loginData so that we aren't sensitive to the capitalization of a user's name for
  //methods of LoginTracker.
  private var loginData: Map[UserID,LoginData] = Map()
  private var lastActive: Timestamp = Timestamp.get

  private var userAndParentAuth: Map[Auth,(Username,Option[Auth])] = Map()

  private def findOrAddLoginData(user: SimpleUserInfo): LoginData = synchronized {
    val lowercaseName = user.name.toLowerCase
    val ld = loginData.getOrElse(lowercaseName, new LoginData(user))
    loginData = loginData + (lowercaseName -> ld)
    ld
  }

  /* Returns the LoginData for a user if that user is logged in */
  private def getLoginData(username: Username, auth: Auth): Option[LoginData] = synchronized {
    val lowercaseName = username.toLowerCase
    loginData.get(lowercaseName).flatMap { ld =>
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
      userAndParentAuth = userAndParentAuth + (auth -> ((user.name,parentAuth)))
      ld.lastActive = now
      lastActive = now
      auth
    }
  }

  //These are not sensitive to capitalization of user's name
  def isLoggedIn(username: Username, auth: Auth): Boolean = synchronized {
    getLoginData(username,auth).nonEmpty
  }
  def isUserLoggedIn(username: Username): Boolean = synchronized {
    val lowercaseName = username.toLowerCase
    loginData.get(lowercaseName).nonEmpty
  }
  def isAuthLoggedIn(auth: Auth): Boolean = synchronized {
    userAndParentAuth.contains(auth)
  }
  def isAnyoneLoggedIn: Boolean = synchronized {
    loginData.nonEmpty
  }

  def userOfAuth(auth: Auth): Option[SimpleUserInfo] = synchronized {
    userAndParentAuth.get(auth).flatMap { case (username,_) =>
      val lowercaseName = username.toLowerCase
      loginData.get(lowercaseName).map(_.info)
    }
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
      val (_,parentAuth) = userAndParentAuth(auth)
      parentAuth.foreach { parentAuth => parent.get.heartbeat(username, parentAuth, now) }
      ld.info
    }
  }

  /* Same as [heartbeat], but performs a lookup to find the username using only the auth */
  def heartbeatAuth(auth: Auth, now: Timestamp): Option[SimpleUserInfo] = synchronized {
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
    val lowercaseName = username.toLowerCase
    loginData = loginData - lowercaseName
  }

  /* Log out all users except the list specified */
  def logoutAllExcept(users: List[Username]): Unit = synchronized {
    val lowercaseNames = users.map(_.toLowerCase)
    loginData = loginData.filter { case (lowercaseName,_) =>
      val shouldKeep = lowercaseNames.contains(lowercaseName)
      shouldKeep
    }
  }

  /* Log out all auths that have not been active recently enough or whose parents were logged out.
   * Returns all users all of whose auths were logged out. */
  def doTimeouts(now: Timestamp): List[Username] = synchronized {
    var usersTimedOut: List[Username] = List()
    loginData = loginData.filter { case (_,ld) =>
      ld.auths = ld.auths.filter { case (auth,time) =>
        //Should we keep this auth of this user logged in?
        val shouldKeep =
          //Only if it's within the inactivity timeout and...
          now < time + inactivityTimeout &&
          userAndParentAuth(auth)._2.forall { parentAuth =>
            parent.get.userOfAuth(parentAuth) match {
              case None =>
                //No, we don't want to keep this auth
                false
              case Some(info) =>
                //Also update our userinfo from the parent if desired
                if(updateInfosFromParent)
                  ld.info = info
                //Yes, we do want to keep.
                true
            }
          }

        if(!shouldKeep)
          userAndParentAuth = userAndParentAuth - auth
        shouldKeep
      }
      val shouldKeep = ld.auths.nonEmpty
      if(!shouldKeep)
        usersTimedOut = ld.info.name :: usersTimedOut
      shouldKeep
    }
    usersTimedOut
  }


  /* Updates the SimpleUserInfo for the specified user if that user is logged in */
  def updateInfo(user: SimpleUserInfo): Unit = synchronized {
    val lowercaseName = user.name.toLowerCase
    loginData.get(lowercaseName).foreach { ld =>
      ld.info = user
    }
  }

}
