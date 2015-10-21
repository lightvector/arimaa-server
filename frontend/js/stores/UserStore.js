var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var EventEmitter = require('events').EventEmitter;
var cookie = require('react-cookie');

var CHANGE_EVENT = 'login-or-registration-change'; //TODO: rename this later
var GAME_CHANGE_EVENT = 'game-change'; //player made move, timeout, etc
var GAME_META_CHANGE_EVENT = 'meta-game-change'; //player joined, left

var openGames = {};   //Set of open gameIDs, dictionary mapping gameID -> gameMetadata
var activeGames = {}; //Set of active gameIDs, dictionary mapping gameID -> gameMetadata

var ownOpenGames = {};         //Games the user created OR that directly involve the current player
var ownActiveGames = {};       //Games the user created OR that directly involve the current player
var joinableOpenGames = {};    //Games the user can join from other users
var watchableOpenGames = {};   //Games that the user cannot join but can watch
var watchableActiveGames = {}; //Games that the user cannot join but can watch


//TODO not included: game rooms with 2 people that haven't started, private games?

var messageText = "";
var errorText = "";

const UserStore = Object.assign({}, EventEmitter.prototype, {
  emitChange: function() {this.emit(CHANGE_EVENT);},
  emitGameMetaChange: function() {this.emit(GAME_META_CHANGE_EVENT);},
  emitGameChange: function() {this.emit(GAME_CHANGE_EVENT);},

  addChangeListener: function(callback) {this.on(CHANGE_EVENT, callback);},
  addGameMetaChangeListener: function(callback) {this.on(GAME_META_CHANGE_EVENT, callback);},
  addGameChangeListener: function(callback) {this.on(GAME_CHANGE_EVENT, callback);},

  removeChangeListener: function(callback) {this.removeListener(CHANGE_EVENT, callback);},
  removeGameMetaChangeListener: function(callback) {this.removeListener(GAME_META_CHANGE_EVENT, callback);},
  removeGameChangeListener: function(callback) {this.removeListener(GAME_CHANGE_EVENT, callback);},

  //use this function later for both registration and login errors
  getLoginState: function() {
    return {message: messageText, error: errorText};
  },

  getUsername: function() {
    return cookie.load('username');
  },

  siteAuthToken: function() {
    return cookie.load('siteAuth');
  },

  gameAuthToken: function() {
    return cookie.load('gameAuth');
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

  dispatcherIndex: ArimaaDispatcher.register(function(action) {
    switch (action.actionType) {
    case SiteConstants.REGISTRATION_FAILED:
    case SiteConstants.LOGIN_FAILED:
    case SiteConstants.FORGOT_PASSWORD_FAILED:
    case SiteConstants.RESET_PASSWORD_FAILED:
      messageText = "";
      errorText = action.reason;
      UserStore.emitChange();
      break;
    case SiteConstants.REGISTRATION_SUCCESS:
    case SiteConstants.LOGIN_SUCCESS:
      messageText = "";
      errorText = "";
      UserStore.emitChange();
      break;
    case SiteConstants.FORGOT_PASSWORD_SUCCESS:
    case SiteConstants.RESET_PASSWORD_SUCCESS:
      messageText = action.reason;
      errorText = "";
      UserStore.emitChange();
      break;
    case SiteConstants.OPEN_GAMES_LIST:
      var oldOpenGames = openGames;
      var username = UserStore.getUsername();
      openGames = {};
      action.metadatas.forEach(function(metadata){
        openGames[metadata.id] = metadata;
        if(metadata.openGameData.creator.name === username ||
           metadata.gUser == username ||
           metadata.sUser == username) {
          ownOpenGames[metadata.id] = metadata;
        }
        else if(metadata.gUser === undefined || metadata.sUser === undefined) {
          joinableOpenGames[metadata.id] = metadata;
        }
        else {
          watchableOpenGames[metadata.id] = metadata;
        }

        //TODO
        //If this is the first time we've seen this game, start a loop for update its detailed metadata
        // if(!(metadata.id in oldOpenGames)) {
        //   SiteActions.beginCreatedGameMetadataLoop(metadata.id);
        // }
      });
      UserStore.emitGameMetaChange();
      break;
    case SiteConstants.ACTIVE_GAMES_LIST:
      var username = UserStore.getUsername();
      activeGames = {};
      action.metadatas.forEach(function(metadata){
        activeGames[metadata.id] = metadata;
        if(metadata.gUser == username ||
           metadata.sUser == username) {
          ownActiveGames[metadata.id] = metadata;
        }
        else {
          watchableActiveGames[metadata.id] = metadata;
        }
      });
      UserStore.emitGameMetaChange();
      break;
        var oldCreatedGames = createdGames;
    case SiteConstants.GAME_METADATA_UPDATE:
      if(metadata.id in openGames) openGames[metadata.id] = metadata;
      if(metadata.id in activeGames) activeGames[metadata.id] = metadata;
      if(metadata.id in ownOpenGames) ownOpenGames[metadata.id] = metadata;
      if(metadata.id in ownActiveGames) ownActiveGames[metadata.id] = metadata;
      if(metadata.id in joinableOpenGames) joinableOpenGames[metadata.id] = metadata;
      if(metadata.id in watchableOpenGames) watchableOpenGames[metadata.id] = metadata;
      if(metadata.id in watchableActiveGames) watchableActiveGames[metadata.id] = metadata;
      UserStore.emitGameMetaChange();
      break;
    case SiteConstants.PLAYER_JOINED:
      var players = action.players; //TODO do something with this...
      UserStore.emitGameMetaChange();
      break;
    default:
      break;
    }
    return true;
  })
});



module.exports = UserStore;
