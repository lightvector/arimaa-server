var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var EventEmitter = require('events').EventEmitter;
var cookie = require('react-cookie');

var CHANGE_EVENT = 'login-or-registration-change'; //TODO: rename this later
var GAME_CHANGE_EVENT = 'game-change'; //player made move, timeout, etc
var GAME_META_CHANGE_EVENT = 'meta-game-change' //player joined, left

var createdGames = []; //games the user created
var openGames = []; //games the user can join
var ongoingGames = []; //games you can spectate
//not included: game rooms with 2 people that haven't started, private games?

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

  getOpenGames: function() {
    return openGames;
  },

  getCreatedGames: function() {
    return createdGames;
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
      case SiteConstants.PLAYER_JOINED:
        var players = action.players; //do something with this...
        UserStore.emitGameMetaChange();
      case SiteConstants.GAME_STATUS_UPDATE:
        break;
      case SiteConstants.OPEN_GAMES_LIST:
        var oldCreatedGames = createdGames;
        createdGames = [];
        openGames = [];
        action.metadatas.forEach(function(metadata){
          if(metadata.openGameData.creator.name === UserStore.getUsername()) {
            createdGames.push(metadata);
            //Check if this is the first time we've seen this game
            var isNewCreatedGame = true;
            for(var i = 0; i<oldCreatedGames.length; i++) {
              if(oldCreatedGames[i].id == metadata.id) {
                isNewCreatedGame = false;
                break;
              }
            }
            //If this is the first time we've seen this game, start a loop for update its detailed state
            if(isNewCreatedGame) {
              SiteActions.beginCreatedGameStateLoop(metadata.id);
            }

          } else {
            openGames.push(metadata);
          }
        });
        UserStore.emitGameMetaChange();
        break;
      default:
        break;
    }
    return true;
  })
});



module.exports = UserStore;
