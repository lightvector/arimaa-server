var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var EventEmitter = require('events').EventEmitter;
var Utils = require('../utils/Utils.js');

var cookie = require('react-cookie');

var CHANGE_EVENT = 'USERSTORE-CHANGE';
var NEW_OPEN_JOINED_GAME = 'USERSTORE-NEW-OPEN-JOINED-GAME';
var NEW_POPUP_MESSAGE = 'USERSTORE-NEW-POPUP-MESSAGE';

//STATE/MODEL-----------------------------------------------------------------
//All of the state associated with being logged into the Arimaa gameroom

var openGames = {};   //Open games, dictionary mapping gameID -> gameMetadata
var activeGames = {}; //Active games, dictionary mapping gameID -> gameMetadata

var ownOpenGames = {};         //Games the user created OR that directly involve the current player
var ownActiveGames = {};       //Games the user created OR that directly involve the current player
var joinableOpenGames = {};    //Games the user can join from other users
var watchableOpenGames = {};   //Games that the user cannot join but can watch
var watchableActiveGames = {}; //Games that the user cannot join but can watch

var joinedGameAuths = {};    //GameAuths for games that we've joined
var leftCreatedGameIDs = {}; //GameIDs for games that we've left that we created
var gamesWeAreLeaving = {};  //Games that we initiated the leaving from

var recentHighlightGameIDs = {}; //Games for which something recently changed that should cause them to flash a highlight
var recentPlayingGameIDs = {};   //Games that we recently started playing

var usersLoggedIn = []; //List of users logged in

var messageText = "";
var errorText = "";

//IMPLEMENTATION---------------------------------------------------------------

const UserStore = Object.assign({}, EventEmitter.prototype, {

  //Subscribe to changes to any of the above state/model
  addChangeListener: function(callback) {this.on(CHANGE_EVENT, callback);},
  removeChangeListener: function(callback) {this.removeListener(CHANGE_EVENT, callback);},
  emitChange: function() {this.emit(CHANGE_EVENT);},

  //Subscribe to the detection of new open joined games
  addNewOpenJoinedGameListener: function(callback) {this.on(NEW_OPEN_JOINED_GAME, callback);},
  removeNewOpenJoinedGameListener: function(callback) {this.removeListener(NEW_OPEN_JOINED_GAME, callback);},
  emitNewOpenJoinedGame: function(gameID) {
    //Make asynchronous
    setTimeout(function () {UserStore.emit(NEW_OPEN_JOINED_GAME, gameID);}, 1);
  },

  //Subscribe to the popup-worthy messages
  addPopupMessageListener: function(callback) {this.on(NEW_POPUP_MESSAGE, callback);},
  removePopupMessageListener: function(callback) {this.removeListener(NEW_POPUP_MESSAGE, callback);},
  emitPopupMessage: function(message) {
    //Make asynchronous
    setTimeout(function () {UserStore.emit(NEW_POPUP_MESSAGE, message);}, 1);
  },

  getMessageError: function() {
    return {message: messageText, error: errorText};
  },

  getUsername: function() {
    return cookie.load('username');
  },

  siteAuthToken: function() {
    return cookie.load('siteAuth');
  },

  getUsersLoggedIn: function() {
    return usersLoggedIn;
  },

  getOwnOpenGamesDict: function() {
    return ownOpenGames;
  },

  getOwnGames: function() {
    var arr = [];
    for(var key in ownActiveGames)
      arr.push(ownActiveGames[key]);
    for(var key in ownOpenGames)
      arr.push(ownOpenGames[key]);
    return arr;
  },
  getJoinableOpenGames: function() {
    var arr = [];
    for(var key in joinableOpenGames)
      arr.push(joinableOpenGames[key]);
    return arr;
  },
  getWatchableGames: function() {
    var arr = [];
    for(var key in watchableActiveGames)
      arr.push(watchableActiveGames[key]);
    for(var key in watchableOpenGames)
      arr.push(watchableOpenGames[key]);
    return arr;
  },

  getOpenGame: function(gameID) {
    if(gameID in openGames)
      return openGames[gameID];
    return null;
  },

  getJoinedGameAuth: function(gameID) {
    if(gameID in joinedGameAuths)
      return joinedGameAuths[gameID];
    return null;
  },

  getRecentHighlightGames: function() {
    return recentHighlightGameIDs;
  },
  getRecentPlayingGames: function() {
    return recentPlayingGameIDs;
  },

  getGame: function(gameID) {
    if(gameID in openGames) return openGames[gameID];
    if(gameID in activeGames) return activeGames[gameID];
    return null;
  },

  removeGame: function(gameID) {
    if(gameID in openGames) delete openGames[gameID];
    if(gameID in activeGames) delete activeGames[gameID];
    if(gameID in ownOpenGames) delete ownOpenGames[gameID];
    if(gameID in ownActiveGames) delete ownActiveGames[gameID];
    if(gameID in joinableOpenGames) delete joinableOpenGames[gameID];
    if(gameID in watchableOpenGames) delete watchableOpenGames[gameID];
    if(gameID in watchableActiveGames) delete watchableActiveGames[gameID];
  },

  flashHighlight: function(gameID) {
    recentHighlightGameIDs[gameID] = true;
    setTimeout(function() {
      Utils.scheduleOnNextFocus(function() {
        if(gameID in recentHighlightGameIDs) {
          delete recentHighlightGameIDs[gameID];
          UserStore.emitChange();
        }
      });
    }, SiteConstants.VALUES.HIGHLIGHT_FLASH_TIMEOUT * 1000);
  },
  flashPlayingGame: function(gameID) {
    recentPlayingGameIDs[gameID] = true;
    setTimeout(function() {
      Utils.scheduleOnNextFocus(function() {
        if(gameID in recentPlayingGameIDs) {
          delete recentPlayingGameIDs[gameID];
          UserStore.emitChange();
        }
      });
    }, SiteConstants.VALUES.HIGHLIGHT_FLASH_TIMEOUT * 1000);
  },

  
  addGame: function(metadata) {
    var username = UserStore.getUsername();
    var gameID = metadata.gameID;
    var prevGame = UserStore.getGame(gameID);
    var prevGameIfOpen = UserStore.getOpenGame(gameID);

    //Remove from any existing lists so we can re-add
    UserStore.removeGame(gameID);

    //First, update all lists
    if(metadata.openGameData !== undefined) {
      //Prevent races where we try to add a metadata back for something we successfully left and closed
      if(metadata.gameID in leftCreatedGameIDs)
        return;

      openGames[gameID] = metadata;
      if(metadata.openGameData.creator.name === username ||
         (metadata.gUser !== undefined && metadata.gUser.name == username) ||
         (metadata.sUser !== undefined && metadata.sUser.name == username)) {
        ownOpenGames[gameID] = metadata;
      }
      else if(metadata.gUser === undefined || metadata.sUser === undefined) {
        joinableOpenGames[gameID] = metadata;
      }
      else {
        watchableOpenGames[gameID] = metadata;
      }
    }
    else if(metadata.activeGameData !== undefined) {
      activeGames[gameID] = metadata;
      if(metadata.gUser.name == username ||
         metadata.sUser.name == username) {
        ownActiveGames[gameID] = metadata;
      }
      else {
        watchableActiveGames[gameID] = metadata;
      }
    }

    //Next, check if we need to do special things regarding changes in join status
    var newJoined = [];

    //Game is open
    if(metadata.openGameData !== undefined) {
      for(var i = 0; i<metadata.openGameData.joined.length; i++) {
        newJoined.push(metadata.openGameData.joined[i].name);
      }

      //If we created this game and someone new joined, alert
      if(metadata.openGameData.creator.name == username) {
        for(var i = 0; i<newJoined.length; i++) {
          if(newJoined[i] !== username && (prevGameIfOpen === null || !Utils.isUserJoined(prevGameIfOpen,newJoined[i]))) {
            Utils.flashWindowIfNotFocused("User Joined Game");
            UserStore.flashHighlight(gameID);
          }
        }
      }
      else {
        //If we joined someone else's game and are not joined any longer but the game is still open, alert
        if(prevGameIfOpen !== null && Utils.isUserJoined(prevGameIfOpen,username) && !Utils.isUserJoined(metadata,username)) {
          //Unless we initiated the leave, in which case clear the fact that we did
          if(gameID in gamesWeAreLeaving)
            delete gamesWeAreLeaving[gameID];
          else {
            UserStore.emitPopupMessage("Request to play was declined by other player, or timed out.");
            Utils.flashWindowIfNotFocused("Play Game Declined");
          }
        }
      }
    }

    //Game we joined became an active game
    if(prevGameIfOpen !== null && metadata.activeGameData !== undefined && Utils.isUserJoined(metadata,username)) {
      Utils.flashWindowIfNotFocused("Game Begun");
      UserStore.flashPlayingGame(gameID);
      
      //TODO this doesn't quite work, it gets blocked a lot, maybe we just let the user click on the button...?
      var newWindow = window.open("/game/" + gameID);
      if(newWindow)
        newWindow.focus();
    }


    //If this is the first time we've seen this game, and we're joined to the game, record it so
    //that we can report the event
    if(prevGame === null) {
      if(Utils.isUserJoined(metadata,username))
        UserStore.emitNewOpenJoinedGame(gameID);
    }
  },

  leavingGame: function(gameID) {
    var now = Utils.currentTimeSeconds();
    gamesWeAreLeaving[gameID] = now;
    //Give it 30 seconds, if after then we haven't seen us leave the game but then we see a leave afterwards, assume we didn't initiate the leave
    setTimeout(function () {if(gamesWeAreLeaving[gameID] == now) delete gamesWeAreLeaving[gameID];}, 30000);
  },

  dispatcherIndex: ArimaaDispatcher.register(function(action) {
    switch (action.actionType) {
    case SiteConstants.ACTIONS.REGISTRATION_FAILED:
    case SiteConstants.ACTIONS.LOGIN_FAILED:
    case SiteConstants.ACTIONS.LOGOUT_FAILED:
    case SiteConstants.ACTIONS.FORGOT_PASSWORD_FAILED:
    case SiteConstants.ACTIONS.RESET_PASSWORD_FAILED:
    case SiteConstants.ACTIONS.GAMEROOM_UPDATE_FAILED:
    case SiteConstants.ACTIONS.CREATE_GAME_FAILED:
      messageText = "";
      errorText = action.reason;
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.REGISTRATION_SUCCESS:
    case SiteConstants.ACTIONS.LOGIN_SUCCESS:
      messageText = "";
      errorText = "";
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.FORGOT_PASSWORD_SUCCESS:
    case SiteConstants.ACTIONS.RESET_PASSWORD_SUCCESS:
      messageText = action.reason;
      errorText = "";
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.USERS_LOGGED_IN_LIST:
      errorText = "";
      usersLoggedIn = action.data.users;
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.OPEN_GAMES_LIST:
      errorText = "";
      var newListIDs = {};
      action.metadatas.forEach(function(metadata){
        //Keep the highest sequence number
        if(!(metadata.gameID in openGames && openGames[metadata.gameID].sequence > metadata.sequence))
          UserStore.addGame(metadata);
        newListIDs[metadata.gameID] = true;
      });

      //Filter out any stale open games
      for(var gameID in openGames) {
        if(!(gameID in newListIDs))
          UserStore.removeGame(gameID);
      }
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.ACTIVE_GAMES_LIST:
      errorText = "";
      var newListIDs = {};
      action.metadatas.forEach(function(metadata){
        //Keep the highest sequence number
        if(!(metadata.gameID in activeGames && activeGames[metadata.gameID].sequence > metadata.sequence))
          UserStore.addGame(metadata);
        newListIDs[metadata.gameID] = true;
      });

      //Filter out any stale active games
      for(var gameID in activeGames) {
        if(!(gameID in newListIDs))
          UserStore.removeGame(gameID);
      }
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.GAME_METADATA_UPDATE:
      var metadata = action.metadata;
      //Keep the highest sequence number
      if(metadata.gameID in openGames && openGames[metadata.gameID].sequence > metadata.sequence)
        break;
      else if(metadata.gameID in activeGames && activeGames[metadata.gameID].sequence > metadata.sequence)
        break;

      UserStore.addGame(metadata);
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.GAME_REMOVED:
      var gameID = action.gameID;
      UserStore.removeGame(gameID);
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.GAME_JOINED:
      joinedGameAuths[action.gameID] = action.gameAuth;
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.HEARTBEAT_FAILED:
      delete joinedGameAuths[action.gameID];
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.LEAVING_GAME:
      UserStore.leavingGame(action.gameID);
      UserStore.emitChange();
      break;
    case SiteConstants.ACTIONS.LEAVE_GAME_SUCCESS:
      //If we created the game and it was an open game, record so and eliminate the game from our store
      if(action.gameID in openGames &&
         openGames[action.gameID].openGameData !== undefined &&
         openGames[action.gameID].openGameData.creator !== undefined &&
         openGames[action.gameID].openGameData.creator.name == UserStore.getUsername()) {
        leftCreatedGameIDs[action.gameID] = action.gameID;
        if(action.gameID in openGames) delete openGames[action.gameID];
        if(action.gameID in ownOpenGames) delete ownOpenGames[action.gameID];
        if(action.gameID in joinableOpenGames) delete joinableOpenGames[action.gameID];
        if(action.gameID in watchableOpenGames) delete watchableOpenGames[action.gameID];
        UserStore.emitChange();
      }
      break;
    default:
      break;
    }
    return true;
  })
});



module.exports = UserStore;
