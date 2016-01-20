var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteConstants = require('../constants/SiteConstants.js');
var UserStore = require('../stores/UserStore.js');
var Utils = require('../utils/Utils.js');
var cookie = require('react-cookie');

const FUNC_NOP = function(){};

//Actions for the main site and gameroom
var SiteActions = {

  checkCookiesEnabled: function() {
    if(!navigator.cookieEnabled) {
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.ACTIONS.LOGIN_FAILED,
        reason: "Cookies not enabled - please enable or else the site will not work properly (cookies used only for site login state and not for tracking)."
      });
      return false;
    }
    return true;
  },

  login: function(username, password) {
    if(!SiteActions.checkCookiesEnabled())
      return;
    APIUtils.login(username, password, SiteActions.loginSuccess, SiteActions.loginError);
  },
  loginGuest: function(username) {
    if(!SiteActions.checkCookiesEnabled())
      return;
    APIUtils.loginGuest(username, SiteActions.loginSuccess, SiteActions.loginError);
  },
  loginError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LOGIN_FAILED,
      reason: data.error
    });
  },
  loginSuccess: function(data) {
    SiteActions.doLogin(data);
  },

  register: function(username, email, password, priorRating) {
    if(!SiteActions.checkCookiesEnabled())
      return;
    APIUtils.register(username, email, password, priorRating, SiteActions.registerSuccess, SiteActions.registerError);
  },
  registerSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.REGISTRATION_SUCCESS
    });
    SiteActions.doLogin(data);
  },
  registerError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.REGISTRATION_FAILED,
      reason: data.error
    });
  },

  doLogin: function(data) {
    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    cookie.save('username',data.username, {path:'/'});

    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LOGIN_SUCCESS
    });
    window.location.pathname = "/gameroom"; //TODO we should track where the user was before and then redirect there instead
  },

  logout: function() {
    APIUtils.logout(SiteActions.logoutSuccess, SiteActions.logoutError);
  },
  logoutSuccess: function(data) {
    cookie.remove('siteAuth','/');
    cookie.remove('username','/');
    window.location.pathname = "/";
  },
  logoutError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LOGOUT_FAILED,
      reason: data.error
    });
  },

  forgotPassword: function(username) {
    APIUtils.forgotPassword(username, SiteActions.forgotPasswordSuccess, SiteActions.forgotPasswordError);
  },
  forgotPasswordError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.FORGOT_PASSWORD_FAILED,
      reason: data.error
    });
  },
  forgotPasswordSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.FORGOT_PASSWORD_SUCCESS,
      reason: data.message
    });
  },

  resetPassword: function(username, resetAuth, password) {
    APIUtils.resetPassword(username, resetAuth, password, SiteActions.resetPasswordSuccess, SiteActions.resetPasswordError);
  },
  resetPasswordError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.RESET_PASSWORD_FAILED,
      reason: data.error
    });
  },
  resetPasswordSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.RESET_PASSWORD_SUCCESS,
      reason: data.message
    });
  },

  verifyEmail: function(username, verifyAuth) {
    APIUtils.verifyEmail(username, verifyAuth, SiteActions.verifyEmailSuccess, SiteActions.verifyEmailError);
  },
  verifyEmailError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.VERIFY_EMAIL_FAILED,
      reason: data.error
    });
  },
  verifyEmailSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.VERIFY_EMAIL_SUCCESS,
      reason: data.message
    });
  },

  resendVerifyEmail: function(username) {
    APIUtils.resendVerifyEmail(username, SiteActions.resendVerifyEmailSuccess, SiteActions.resendVerifyEmailError);
  },
  resendVerifyEmailError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.RESEND_VERIFY_EMAIL_FAILED,
      reason: data.error
    });
  },
  resendVerifyEmailSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.RESEND_VERIFY_EMAIL_SUCCESS,
      reason: data.message
    });
  },

  goLoginPageIfNotLoggedIn: function() {
    APIUtils.authLoggedIn(SiteActions.goLoginPageIfNotLoggedInSuccess, FUNC_NOP);
  },

  goLoginPageIfNotLoggedInSuccess: function(data) {
    if(!data.value) {
      cookie.remove('siteAuth','/');
      cookie.remove('username','/');
      window.location.pathname = "/";
    }
  },

  createGame: function(opts) {
    APIUtils.createGame(opts, SiteActions.createGameSuccess, SiteActions.createGameError);
  },
  createGameSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_GAME_JOINED,
      gameID: data.gameID,
      gameAuth: data.gameAuth
    });

    SiteActions.beginJoinedOpenGameMetadataLoop(data.gameID,data.gameAuth,true);
  },
  createGameError: function(data) {
    SiteActions.goLoginPageIfNotLoggedIn();
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.CREATE_GAME_FAILED,
      reason: data.error
    });
  },

  joinGame: function(gameID) {
    APIUtils.joinGame(gameID, function(data) {SiteActions.joinGameSuccess(gameID,data);}, SiteActions.goLoginPageIfNotLoggedIn);
  },
  joinGameSuccess: function(gameID, data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_GAME_JOINED,
      gameID: gameID,
      gameAuth: data.gameAuth
    });

    SiteActions.beginJoinedOpenGameMetadataLoop(gameID,data.gameAuth,true);
  },

  acceptUserForGame: function(gameID, gameAuth, username) {
    APIUtils.acceptUserForGame(gameID, gameAuth, username, FUNC_NOP, SiteActions.goLoginPageIfNotLoggedIn);
  },
  declineUserForGame: function(gameID, gameAuth, username) {
    APIUtils.declineUserForGame(gameID, gameAuth, username, FUNC_NOP, SiteActions.goLoginPageIfNotLoggedIn);
  },
  leaveGame: function(gameID, gameAuth) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LEAVING_GAME,
      gameID: gameID
    });
    APIUtils.leaveGame(gameID, gameAuth, function(data) {SiteActions.leaveGameSuccess(gameID,data);}, SiteActions.goLoginPageIfNotLoggedIn);
  },
  leaveGameSuccess: function(gameID,data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LEAVE_GAME_SUCCESS,
      gameID: gameID
    });
  },


  //A single point query to update the list of open games
  getOpenGames: function() {
    APIUtils.getOpenGames(SiteActions.getOpenGamesSuccess, SiteActions.getOpenGamesError);
  },
  getOpenGamesSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.OPEN_GAMES_LIST,
      metadatas: data
    });
  },
  getOpenGamesError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMES_LIST_FAILED,
      reason: "Error getting open/active games, possible network or other connection issues, consider refreshing the page."
    });
    console.log(data);
  },

  //Initiates a loop querying for the list of users logged in every few seconds, continuing forever.
  beginLoginCheckLoopCalled: false,
  beginLoginCheckLoop: function() {
    if(SiteActions.beginLoginCheckLoopCalled)
      return;
    SiteActions.beginLoginCheckLoopCalled = true;

    SiteActions.goLoginPageIfNotLoggedIn();
    setTimeout(SiteActions.continueLoginCheckLoop, SiteConstants.VALUES.LOGIN_CHECK_LOOP_DELAY * 1000);
  },

  continueLoginCheckLoop: function() {
    SiteActions.goLoginPageIfNotLoggedIn();
    setTimeout(SiteActions.continueLoginCheckLoop, SiteConstants.VALUES.LOGIN_CHECK_LOOP_DELAY * 1000);
  },

  updateErrorMessage :
  "Error getting gameroom updates, possible network or other connection issues, consider refreshing the page.",

  //Initiates a loop querying for the list of users logged in every few seconds, continuing forever.
  beginUsersLoggedInLoopCalled: false,
  beginUsersLoggedInLoop: function() {
    if(SiteActions.beginUsersLoggedInLoopCalled)
      return;
    SiteActions.beginUsersLoggedInLoopCalled = true;
    APIUtils.usersLoggedIn(SiteActions.usersLoggedInLoopSuccess, SiteActions.usersLoggedInLoopError);
  },
  usersLoggedInLoopSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.USERS_LOGGED_IN_LIST,
      data: data
    });
    setTimeout(function () {
      APIUtils.usersLoggedIn(SiteActions.usersLoggedInLoopSuccess, SiteActions.usersLoggedInLoopError);
    }, SiteConstants.VALUES.GAME_LIST_LOOP_DELAY * 1000);
  },
  usersLoggedInLoopError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_UPDATE_FAILED,
      reason: SiteActions.updateErrorMessage
    });
    console.log(data);
    setTimeout(function () {
      APIUtils.usersLoggedIn(SiteActions.usersLoggedInLoopSuccess, SiteActions.usersLoggedInLoopError);
    }, SiteConstants.VALUES.GAME_LIST_LOOP_DELAY_ON_ERROR * 1000);
  },

  //Initiates a loop querying for the list of users logged in every few seconds, continuing forever.
  beginNotificationsLoopCalled: false,
  beginNotificationsLoop: function() {
    if(SiteActions.beginNotificationsLoopCalled)
      return;
    SiteActions.beginNotificationsLoopCalled = true;
    var username = UserStore.getUsername();
    APIUtils.getNotifications(username, SiteActions.notificationsLoopSuccess, SiteActions.notificationsLoopError);
  },
  notificationsLoopSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.NOTIFICATIONS_LIST,
      data: data
    });
    var username = UserStore.getUsername();
    setTimeout(function () {
      APIUtils.getNotifications(username, SiteActions.notificationsLoopSuccess, SiteActions.notificationsLoopError);
    }, SiteConstants.VALUES.NOTIFICATIONS_LOOP_DELAY * 1000);
  },
  notificationsLoopError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_UPDATE_FAILED,
      reason: SiteActions.updateErrorMessage
    });
    console.log(data);
    var username = UserStore.getUsername();
    setTimeout(function () {
      APIUtils.getNotifications(username, SiteActions.notificationsLoopSuccess, SiteActions.notificationsLoopError);
    }, SiteConstants.VALUES.NOTIFICATIONS_LOOP_DELAY * 1000);
  },

  //Initiates a loop querying for the list of open games every few seconds, continuing forever.
  beginOpenGamesLoopCalled: false,
  beginOpenGamesLoop: function() {
    if(SiteActions.beginOpenGamesLoopCalled)
      return;
    SiteActions.beginOpenGamesLoopCalled = true;
    APIUtils.getOpenGames(SiteActions.openGamesLoopSuccess, SiteActions.openGamesLoopError);
    UserStore.addNewOpenJoinedGameListener(SiteActions.newOpenJoinedGame);
  },
  openGamesLoopSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.OPEN_GAMES_LIST,
      metadatas: data
    });
    setTimeout(function () {
      APIUtils.getOpenGames(SiteActions.openGamesLoopSuccess, SiteActions.openGamesLoopError);
    }, SiteConstants.VALUES.GAME_LIST_LOOP_DELAY * 1000);
  },
  openGamesLoopError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_UPDATE_FAILED,
      reason: SiteActions.updateErrorMessage
    });
    console.log(data);
    setTimeout(function () {
      APIUtils.getOpenGames(SiteActions.openGamesLoopSuccess, SiteActions.openGamesLoopError);
    }, SiteConstants.VALUES.GAME_LIST_LOOP_DELAY_ON_ERROR * 1000);
  },

  newOpenJoinedGame: function(gameID) {
    //If this game is one we don't have a gameAuth for (ex: user joined this game in a different tab)
    //then join it in this tab as well to obtain a gameAuth so that we can heartbeat the game and keep it alive.
    var gameAuth = UserStore.getJoinedGameAuth(gameID);
    if(gameAuth === null)
      SiteActions.joinGame(gameID);
  },

  //Initiates a loop querying for the list of active games every few seconds, continuing forever.
  beginActiveGamesLoopCalled: false,
  beginActiveGamesLoop: function() {
    if(SiteActions.beginActiveGamesLoopCalled)
      return;
    SiteActions.beginActiveGamesLoopCalled = true;
    APIUtils.getActiveGames(SiteActions.activeGamesLoopSuccess, SiteActions.activeGamesLoopError);
  },
  activeGamesLoopSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.ACTIVE_GAMES_LIST,
      metadatas: data
    });
    setTimeout(function () {
      APIUtils.getActiveGames(SiteActions.activeGamesLoopSuccess, SiteActions.activeGamesLoopError);
    }, SiteConstants.VALUES.GAME_LIST_LOOP_DELAY * 1000);
  },
  activeGamesLoopError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_UPDATE_FAILED,
      reason: SiteActions.updateErrorMessage
    });
    console.log(data);
    setTimeout(function () {
      APIUtils.getActiveGames(SiteActions.activeGamesLoopSuccess, SiteActions.activeGamesLoopError);
    }, SiteConstants.VALUES.GAME_LIST_LOOP_DELAY_ON_ERROR * 1000);
  },


  //Initiates a loop querying for the result of this game metadata so long as the game
  //exists and is open and is a game we created OR that we joined, and so long as
  //the provided auth is latest one we joined with (auth not needed to perform the query, but we only
  //care about rapid updating of metadata if we're joined with the game, and this also gives
  //us deduplication of loops)
  beginJoinedOpenGameMetadataLoop: function(gameID, gameAuth, startHeartbeats) {
    APIUtils.gameMetadata(gameID, 0,
                          function(data) {return SiteActions.joinedOpenGameMetadataSuccess(gameAuth,data,startHeartbeats);},
                          function(data) {return SiteActions.joinedOpenGameMetadataError(gameID,gameAuth,data);});
  },
  joinedOpenGameMetadataSuccess: function(gameAuth,data,startHeartbeats) {
    var seqNum = data.sequence;
    var gameID = data.gameID;
    var storedGameAuth = UserStore.getJoinedGameAuth(gameID);

    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAME_METADATA_UPDATE,
      metadata: data
    });

    //If we've since changed our auth or closed this game, terminate the loop
    if(storedGameAuth === null || storedGameAuth != gameAuth || data.openGameData === undefined)
      return;

    //If we should start heartbeating, do so
    if(startHeartbeats)
      SiteActions.startOpenJoinedHeartbeatLoop(gameID,gameAuth);

    setTimeout(function () {
      APIUtils.gameMetadata(gameID, seqNum+1,
                            function(data) {return SiteActions.joinedOpenGameMetadataSuccess(gameAuth,data,false);},
                            function(data) {return SiteActions.joinedOpenGameMetadataError(gameID,gameAuth,data);});
    }, SiteConstants.VALUES.JOINED_GAME_META_LOOP_DELAY * 1000);
  },
  joinedOpenGameMetadataError: function(gameID,gameAuth,data) {
    var game = UserStore.getOpenGame(gameID);
    var storedGameAuth = UserStore.getJoinedGameAuth(gameID);
    if(storedGameAuth === null || storedGameAuth != gameAuth || game === null)
      return;

    //TODO fragile
    if(data.error == "No game found with the given id") {
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.ACTIONS.GAME_REMOVED,
        gameID: gameID
      });
    }

    console.log(data);
    setTimeout(function () {
      APIUtils.gameMetadata(gameID, 0,
                            function(data) {return SiteActions.joinedOpenGameMetadataSuccess(gameAuth,data,false);},
                            function(data) {return SiteActions.joinedOpenGameMetadataError(gameID,gameAuth,data);});
    }, SiteConstants.VALUES.JOINED_GAME_META_LOOP_DELAY_ON_ERROR * 1000);
  },
  isOwnOpenGameInStore: function(gameID) {
    var games = UserStore.getOwnOpenGamesDict();
    if(!(gameID in games))
      return false;
    return games[gameID] !== undefined;
  },

  //Initiates a loop heartbeating this game so long as this game exists and we're joined with it
  startOpenJoinedHeartbeatLoop: function(gameID, gameAuth) {
    var username = UserStore.getUsername();
    var game = UserStore.getOpenGame(gameID);
    //If we've since changed our auth or closed this game, terminate the loop
    var storedGameAuth = UserStore.getJoinedGameAuth(gameID);
    if(storedGameAuth === null || storedGameAuth != gameAuth || game === null)
      return;
    //If the game is not open, terminate
    if(game.openGameData === undefined)
      return;

    //If we're not one of the players joined to this game, terminate.
    var joined = false;
    for(var j = 0; j<game.openGameData.joined.length; j++) {
      if(game.openGameData.joined[j].name === username) {
        joined = true; break;
      }
    }
    if(!joined)
      return;

    APIUtils.gameHeartbeat(gameID, gameAuth, FUNC_NOP, function (data) {SiteActions.onHeartbeatError(gameID,data);});
    setTimeout(function () {
       SiteActions.startOpenJoinedHeartbeatLoop(gameID, gameAuth);
     }, SiteConstants.VALUES.GAME_HEARTBEAT_PERIOD * 1000);
  },
  onHeartbeatError: function(gameID,data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAMEROOM_HEARTBEAT_FAILED,
      gameID: gameID
    });
  }

};

module.exports = SiteActions;
