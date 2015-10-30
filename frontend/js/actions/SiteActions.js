var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteConstants = require('../constants/SiteConstants.js');
var UserStore = require('../stores/UserStore.js');
var cookie = require('react-cookie');

const FUNC_NOP = function(){};

var SiteActions = {

  login: function(username, password) {
    APIUtils.login(username, password, SiteActions.loginSuccess, SiteActions.loginError);
  },
  loginError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.LOGIN_FAILED,
      reason: data.error
    });
  },
  loginSuccess: function(data) {
    console.log('login success');

    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    cookie.save('username',data.username, {path:'/'});

    window.location.pathname = "/gameroom"; //TODO we should track where the user was before and then redirect there instead
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.LOGIN_SUCCESS
    });
  },

  register: function(username, email, password) {
    console.log("do some registering");
    APIUtils.register(username, email, password, SiteActions.registerSuccess, SiteActions.registerError);
  },
  registerSuccess: function(data) {
    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    cookie.save('username',data.username, {path:'/'});
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.REGISTRATION_SUCCESS
    });
    window.location.pathname = "/gameroom"; //TODO we should track where the user was before and then redirect there instead
  },
  registerError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.REGISTRATION_FAILED,
      reason: data.error
    });
  },

  logout: function() {
    APIUtils.logout();
    cookie.remove('siteAuth','/');//TODO: create logout_success/error and remove cookie there
    cookie.remove('username','/');
    window.location.pathname = "/login";
  },

  forgotPassword: function(username) {
    APIUtils.forgotPassword(username, SiteActions.forgotPasswordSuccess, SiteActions.forgotPasswordError);
  },
  forgotPasswordError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.FORGOT_PASSWORD_FAILED,
      reason: data.error
    });
  },
  forgotPasswordSuccess: function(data) {
    console.log('forgot password success');
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.FORGOT_PASSWORD_SUCCESS,
      reason: data.message
    });
  },

  resetPassword: function(username, resetAuth, password) {
    APIUtils.resetPassword(username, resetAuth, password, SiteActions.resetPasswordSuccess, SiteActions.resetPasswordError);
  },
  resetPasswordError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.RESET_PASSWORD_FAILED,
      reason: data.error
    });
  },
  resetPasswordSuccess: function(data) {
    console.log('reset password success');
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.RESET_PASSWORD_SUCCESS,
      reason: data.message
    });
  },

  createGame: function(gameType) {
    console.log("creating game with type: ", gameType);
    var opts = {
      tc: {
        //TODO specify TC
        initialTime: 3600 //1 hr
      },
      rated: false,
      gameType: gameType,
      siteAuth: UserStore.siteAuthToken()
    };
    APIUtils.createGame(opts, SiteActions.createGameSuccess, SiteActions.createGameError);
  },
  createGameSuccess: function(data) {
    console.log("create game data ", data);

    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.GAME_JOINED,
      gameID: data.gameID,
      gameAuth: data.gameAuth
    });

    SiteActions.beginOwnOpenGameMetadataLoop(data.gameID);
    SiteActions.startOpenJoinedHeartbeatLoop(data.gameID,data.gameAuth);
  },
  createGameError: function(data) {
    //TODO
    console.log(data);
  },

  joinGame: function(gameID) {
    APIUtils.joinGame(gameID, function(data) {SiteActions.joinGameSuccess(gameID,data);}, FUNC_NOP);
  },
  joinGameSuccess: function(gameID, data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.GAME_JOINED,
      gameID: gameID,
      gameAuth: data.gameAuth
    });

    SiteActions.beginOwnOpenGameMetadataLoop(gameID);
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
      actionType: SiteConstants.LEAVE_GAME_SUCCESS,
      gameID: gameID
    });
  },


  //A single point query to update the list of open games
  getOpenGames: function() {
    APIUtils.getOpenGames(SiteActions.getOpenGamesSuccess, SiteActions.getOpenGamesError);
  },
  getOpenGamesSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.OPEN_GAMES_LIST,
      metadatas: data
    });
  },
  getOpenGamesError: function(data) {
    //TODO
    console.log(data);
  },

  //Initiates a loop querying for the list of open games every few seconds, continuing forever.
  beginOpenGamesLoop: function() {
    APIUtils.getOpenGames(SiteActions.openGamesLoopSuccess, SiteActions.openGamesLoopError);
    UserStore.addNewOpenJoinedGameListener(SiteActions.newOpenJoinedGame);
  },
  openGamesLoopSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.OPEN_GAMES_LIST,
      metadatas: data
    });

    //TODO sleep 6s
    setTimeout(function () {
      APIUtils.getOpenGames(SiteActions.openGamesLoopSuccess, SiteActions.openGamesLoopError);
    }, 6000);
  },
  openGamesLoopError: function(data) {
    //TODO
    console.log(data);
    //TODO sleep 30s
    setTimeout(function () {
      APIUtils.getOpenGames(SiteActions.openGamesLoopSuccess, SiteActions.openGamesLoopError);
    }, 30000);
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
      actionType: SiteConstants.ACTIVE_GAMES_LIST,
      metadatas: data
    });

    //TODO sleep 6s
    setTimeout(function () {
      APIUtils.getActiveGames(SiteActions.activeGamesLoopSuccess, SiteActions.activeGamesLoopError);
    }, 6000);
  },
  activeGamesLoopError: function(data) {
    //TODO
    console.log(data);
    //TODO sleep 30s
    setTimeout(function () {
      APIUtils.getActiveGames(SiteActions.activeGamesLoopSuccess, SiteActions.activeGamesLoopError);
    }, 30000);
  },


  //Initiates a loop querying for the result of this game metadata so long as the game
  //exists and is open and is a game we created OR that directly involves us
  beginOwnOpenGameMetadataLoop: function(gameID) {
    APIUtils.gameMetadata(gameID, 0, SiteActions.ownOpenGameMetadataSuccess, function(data) {return SiteActions.ownOpenGameMetadataError(gameID,data);});
  },
  ownOpenGameMetadataSuccess: function(data) {
    var seqNum = data.sequence;
    var gameID = data.gameID;

    if(data.openGameData && data.openGameData.joined.length > 1) {
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.PLAYER_JOINED,
        players: data.openGameData.joined, //note this includes the creator
        gameID: gameID
      });
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.GAME_METADATA_UPDATE,
        metadata: data
      });
    }

    //TODO sleep 200ms
    setTimeout(function () {
      APIUtils.gameMetadata(gameID, seqNum+1,
                            SiteActions.ownOpenGameMetadataSuccess,
                            function(data) {return SiteActions.ownOpenGameMetadataError(gameID,data);});
    }, 200);

  },
  ownOpenGameMetadataError: function(gameID,data) {
    //TODO
    console.log(data);
    if(SiteActions.isOwnOpenGameInStore(gameID)) {
      //TODO sleep 2000 ms
      setTimeout(function () {
        APIUtils.gameMetadata(gameID, 0, SiteActions.ownOpenGameMetadataSuccess, function(data) {return SiteActions.ownOpenGameMetadataError(gameID,data);});
      }, 2000);
    }
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
     setTimeout(function () {SiteActions.startOpenJoinedHeartbeatLoop(gameID, gameAuth);}, 5000);
  },
  onHeartbeatError: function(gameID,data) {
    //TODO
    console.log(data);
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.HEARTBEAT_FAILED,
      gameID: gameID
    });
  }

};

module.exports = SiteActions;
