var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteConstants = require('../constants/SiteConstants.js');
var UserStore = require('../stores/UserStore.js');
var cookie = require('react-cookie');

const FUNC_NOP = function(){};

//Actions for the main site and gameroom
var SiteActions = {

  login: function(username, password) {
    APIUtils.login(username, password, SiteActions.loginSuccess, SiteActions.loginError);
  },
  loginError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LOGIN_FAILED,
      reason: data.error
    });
  },
  loginSuccess: function(data) {
    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    cookie.save('username',data.username, {path:'/'});

    window.location.pathname = "/gameroom"; //TODO we should track where the user was before and then redirect there instead
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.LOGIN_SUCCESS
    });
  },

  register: function(username, email, password) {
    APIUtils.register(username, email, password, SiteActions.registerSuccess, SiteActions.registerError);
  },
  registerSuccess: function(data) {
    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    cookie.save('username',data.username, {path:'/'});
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.REGISTRATION_SUCCESS
    });
    window.location.pathname = "/gameroom"; //TODO we should track where the user was before and then redirect there instead
  },
  registerError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.REGISTRATION_FAILED,
      reason: data.error
    });
  },

  logout: function() {
    APIUtils.logout(SiteActions.logoutSuccess, SiteActions.logoutError);
  },
  logoutSuccess: function(data) {
    cookie.remove('siteAuth','/');
    cookie.remove('username','/');
    window.location.pathname = "/login";
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

  createGame: function(opts) {
    APIUtils.createGame(opts, SiteActions.createGameSuccess, SiteActions.createGameError);
  },

  createStandardGame: function(tc,rated) {
    var opts = {
      tc: tc,
      rated: rated,
      gameType: "standard",
      siteAuth: UserStore.siteAuthToken()
    };
    APIUtils.createGame(opts, SiteActions.createGameSuccess, SiteActions.createGameError);
  },
  createGameSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAME_JOINED,
      gameID: data.gameID,
      gameAuth: data.gameAuth
    });

    SiteActions.beginJoinedOpenGameMetadataLoop(data.gameID,data.gameAuth);
    SiteActions.startOpenJoinedHeartbeatLoop(data.gameID,data.gameAuth);
  },
  createGameError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.CREATE_GAME_FAILED,
      reason: data.error
    });
  },

  joinGame: function(gameID) {
    APIUtils.joinGame(gameID, function(data) {SiteActions.joinGameSuccess(gameID,data);}, FUNC_NOP);
  },
  joinGameSuccess: function(gameID, data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAME_JOINED,
      gameID: gameID,
      gameAuth: data.gameAuth
    });

    SiteActions.beginJoinedOpenGameMetadataLoop(gameID,data.gameAuth);
    SiteActions.startOpenJoinedHeartbeatLoop(gameID,data.gameAuth);
  },

  acceptUserForGame: function(gameID, gameAuth, username) {
    APIUtils.acceptUserForGame(gameID, gameAuth, username, FUNC_NOP, FUNC_NOP);
  },
  declineUserForGame: function(gameID, gameAuth, username) {
    APIUtils.declineUserForGame(gameID, gameAuth, username, FUNC_NOP, FUNC_NOP);
  },
  leaveGame: function(gameID, gameAuth) {
    APIUtils.leaveGame(gameID, gameAuth, function(data) {SiteActions.leaveGameSuccess(gameID,data);}, FUNC_NOP);
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

  //Initiates a loop querying for the list of open games every few seconds, continuing forever.
  beginOpenGamesLoop: function() {
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
      actionType: SiteConstants.ACTIONS.GAMES_LIST_FAILED,
      reason: "Error getting open/active games, possible network or other connection issues, consider refreshing the page."
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
  beginActiveGamesLoop: function() {
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
      actionType: SiteConstants.ACTIONS.GAMES_LIST_FAILED,
      reason: "Error getting open/active games, possible network or other connection issues, consider refreshing the page."
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
  beginJoinedOpenGameMetadataLoop: function(gameID, gameAuth) {
    APIUtils.gameMetadata(gameID, 0,
                          function(data) {return SiteActions.joinedOpenGameMetadataSuccess(gameAuth,data);},
                          function(data) {return SiteActions.joinedOpenGameMetadataError(gameID,gameAuth,data);});
  },
  joinedOpenGameMetadataSuccess: function(gameAuth,data) {
    var seqNum = data.sequence;
    var gameID = data.gameID;

    if(data.openGameData && data.openGameData.joined.length > 1) {
      //TODO only report when it actually differs from previous
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.ACTIONS.PLAYER_JOINED,
        players: data.openGameData.joined, //note this includes the creator
        gameID: gameID
      });
    }

    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.ACTIONS.GAME_METADATA_UPDATE,
      metadata: data
    });

    //If we've since changed our auth or closed this game, terminate the loop
    var game = UserStore.getOpenGame(gameID);
    var storedGameAuth = UserStore.getJoinedGameAuth(gameID);
    if(storedGameAuth === null || storedGameAuth != gameAuth || game === null)
      return;

    setTimeout(function () {
      APIUtils.gameMetadata(gameID, seqNum+1,
                            function(data) {return SiteActions.joinedOpenGameMetadataSuccess(gameAuth,data);},
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
                            function(data) {return SiteActions.joinedOpenGameMetadataSuccess(gameAuth,data);},
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
      if(game.openGameData.joined[j].name == username) {
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
      actionType: SiteConstants.ACTIONS.HEARTBEAT_FAILED,
      gameID: gameID
    });
  }

};

module.exports = SiteActions;
