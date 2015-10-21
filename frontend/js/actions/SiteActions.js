var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteConstants = require('../constants/SiteConstants.js');
var UserStore = require('../stores/UserStore.js');
var cookie = require('react-cookie');

const FUNC_NOP = function(){}

var seqNum = 0; //maybe move this to the store?


/*********
MOVE ALL API CALLS TO STORES????
********/

var SiteActions = {
  login: function(username, password) {
    APIUtils.login(username, password, this.loginSuccess, this.loginError);
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

    window.location.pathname = "/"; //TODO we should track where the user was before and then redirect there instead
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.LOGIN_SUCCESS
    });
  },

  register: function(username, email, password) {
    console.log("do some registering");
    APIUtils.register(username, email, password, this.registerSuccess, this.registerError);
  },
  registerSuccess: function(data) {
    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    cookie.save('username',data.username, {path:'/'});
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.REGISTRATION_SUCCESS
    });
    window.location.pathname = "/"; //TODO we should track where the user was before and then redirect there instead
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
    window.location.pathname = "/login"; //make the login page nicer
  },

  forgotPassword: function(username) {
    APIUtils.forgotPassword(username, this.forgotPasswordSuccess, this.forgotPasswordError);
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
    APIUtils.resetPassword(username, resetAuth, password, this.resetPasswordSuccess, this.resetPasswordError);
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
    APIUtils.createGame(opts, this.createGameSuccess, this.createGameError);
  },
  createGameSuccess: function(data) {
    console.log("create game data ", data);
    cookie.save('gameAuth',data.gameAuth, {path:'/'}); //TODO need a way to save multiple gameauths

    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.GAME_CREATED,
      gameID: data.gameID,
      gameAuth: data.gameAuth
    });
    APIUtils.gameState(data.gameID, 0, SiteActions.gameStateSuccess, FUNC_NOP);
  },
  createGameError: function(data) {
    //TODO
    console.log(data);
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


  //Initiates a loop querying for the result of this game state and heartbeating it so long as the game
  //exists and is open and is a game we created
  beginCreatedGameStateLoop: function(gameID) {
    APIUtils.gameState(gameID, 0, SiteActions.gameStateSuccess, SiteActions.gameStateError);
  },
  gameStateSuccess: function(data) {
    seqNum = data.sequence;

    if(data.meta.openGameData && data.meta.openGameData.joined.length > 1) {
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.PLAYER_JOINED,
        players: data.meta.openGameData.joined, //note this includes the creator
        gameID: data.meta.id
      });
      ArimaaDispatcher.dispatch({
        actionType: SiteConstants.GAME_STATUS_UPDATE,
        data: data //pass all the data
      });
    }

    //TODO sleep 200ms
    setTimeout(function () {
      APIUtils.gameState(data.meta.id, seqNum+1, SiteActions.gameStateSuccess, SiteActions.gameStateError);
    }, 200);

  },
  gameStateError: function(gameID,data) {
    //TODO
    console.log(data);
    if(createdGameExistsInStore(gameID)) {
      //TODO sleep 2000 ms
      setTimeout(function () {
        APIUtils.gameState(id, 0, SiteActions.gameStateSuccess, SiteActions.gameStateError);
      }, 2000);
    }
  },
  createdGameExistsInStore: function(gameID) {
    var games = UserStore.getCreatedGames();
    for(var i = 0; i<games.length; i++) {
      if(games[i].openGameData !== undefined
         && games[i].id == gameID
         && games[i].openGameData.creator !== undefined
         && games[i].openGameData.creator == UserStore.getUsername()) {
        return true;
      }
    }
    return false;
  },
  startHeartbeatLoop: function(gameID) {
    if(createdGameExistsInStore(gameID)) {
      APIUtils.gameHeartbeat(gameID, FUNC_NOP, this.onHeartbeatError);
      //TODO sleep 5s
      var that = this;
      setTimeout(function () {that.startHeartbeatLoop(gameID);}, 5000);
    }
  },
  onHeartbeatError: function(data) {
    //TODO
    console.log(data);
  },


  //starts a game
  acceptUserForGame: function(gameID, username) {
    APIUtils.acceptUserForGame(gameID, username, FUNC_NOP, FUNC_NOP);
  },
  joinGame: function(gameID) {
    APIUtils.joinGame(gameID, SiteActions.joinGameSuccess, FUNC_NOP);
  },
  joinGameSuccess: function(data) {
    cookie.save('gameAuth',data.gameAuth, {path:'/'});//TODO once again, we'll need a way to save multiple gameauths
  }

};

module.exports = SiteActions;
