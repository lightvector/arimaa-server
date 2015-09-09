var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var EventEmitter = require('events').EventEmitter;
var cookie = require('react-cookie');

var CHANGE_EVENT = 'login-or-registration-change'; //rename this later
var GAME_CHANGE_EVENT = 'game-change'; //player made move, timeout, etc
var GAME_META_CHANGE_EVENT = 'meta-game-change' //player joined, left

var createdGames = []; //games the user created
var openGames = []; //games the user can join
var ongoingGames = []; //games you can spectate
//not included: game rooms with 2 people that haven't started, private games?

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
    return {error: errorText};
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

  getOpenGames: function() {
    return openGames;
  },

  dispatcherIndex: ArimaaDispatcher.register(function(action) {
    switch (action.actionType) {
      case SiteConstants.REGISTRATION_FAILED:
      case SiteConstants.LOGIN_FAILED:
        errorText = action.reason;
        UserStore.emitChange();
        break;
      case SiteConstants.REGISTRATION_SUCCESS:
      case SiteConstants.LOGIN_SUCCESS:
        errorText = "";
        UserStore.emitChange();
        break;
      case SiteConstants.PLAYER_JOINED:
        var players = action.players; //do something with this...
        UserStore.emitGameMetaChange();
      case SiteConstants.GAME_STATUS_UPDATE:
        break;
      case SiteConstants.OPEN_GAMES_LIST:
        openGames = action.metadatas;
        break;
      default:
        break;
    }
    return true;
  })
});



module.exports = UserStore;
