var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var EventEmitter = require('events').EventEmitter;
var cookie = require('react-cookie');

var CHANGE_EVENT = 'USERSTORE-CHANGE';
var NEW_OPEN_JOINED_GAME = 'USERSTORE-NEW-OPEN-JOINED-GAME';

//STATE/MODEL-----------------------------------------------------------------
//All of the state associated with being logged into the Arimaa gameroom

var openGames = {};   //Open games, dictionary mapping gameID -> gameMetadata
var activeGames = {}; //Active games, dictionary mapping gameID -> gameMetadata

var ownOpenGames = {};         //Games the user created OR that directly involve the current player
var ownActiveGames = {};       //Games the user created OR that directly involve the current player
var joinableOpenGames = {};    //Games the user can join from other users
var watchableOpenGames = {};   //Games that the user cannot join but can watch
var watchableActiveGames = {}; //Games that the user cannot join but can watch

var joinedGameAuths = {}; //GameAuths for games that we've joined

var messageText = "";
var errorText = "";

//IMPLEMENTATION---------------------------------------------------------------

const UserStore = Object.assign({}, EventEmitter.prototype, {

  //Subscribe to changes to any of the above state/model
  addChangeListener: function(callback) {this.on(CHANGE_EVENT, callback);},
  removeChangeListener: function(callback) {this.removeListener(CHANGE_EVENT, callback);},
  emitChange: function() {this.emit(CHANGE_EVENT);},

  //Subscribe to the detection of new open joined games
  emitNewOpenJoinedGame: function(gameID) {this.emit(NEW_OPEN_JOINED_GAME, gameID);}, 
  addNewOpenJoinedGameListener: function(callback) {this.on(NEW_OPEN_JOINED_GAME, callback);},
  removeNewOpenJoinedGameListener: function(callback) {this.removeListener(NEW_OPEN_JOINED_GAME, callback);},
  
  getMessageError: function() {
    return {message: messageText, error: errorText};
  },

  getUsername: function() {
    return cookie.load('username');
  },

  siteAuthToken: function() {
    return cookie.load('siteAuth');
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
      var newOpenJoinedGames = [];
      action.metadatas.forEach(function(metadata){
        openGames[metadata.id] = metadata;
        if(metadata.openGameData.creator.name === username ||
           metadata.gUser.name == username ||
           metadata.sUser.name == username) {
          ownOpenGames[metadata.id] = metadata;
        }
        else if(metadata.gUser === undefined || metadata.sUser === undefined) {
          joinableOpenGames[metadata.id] = metadata;
        }
        else {
          watchableOpenGames[metadata.id] = metadata;
        }

        //If this is the first time we've seen this game, and we're joined to the game, record it so
        //that we can report the event
        if(!(metadata.id in oldOpenGames)) {
          var joined = false;
          for(var j = 0; j<metadata.openGameData.joined.length; j++) {
            if(metadata.openGameData.joined[j].name == username) {
              joined = true; break;
            }
          }
          if(joined)
            newOpenJoinedGames.append(metadata);
        }
      });
      UserStore.emitChange();
      for(var metadata in newOpenJoinedGames) {
        UserStore.emitNewOpenJoinedGame(metadata.id);
      }
      break;
    case SiteConstants.ACTIVE_GAMES_LIST:
      var username = UserStore.getUsername();
      activeGames = {};
      action.metadatas.forEach(function(metadata){
        activeGames[metadata.id] = metadata;
        if(metadata.gUser.name == username ||
           metadata.sUser.name == username) {
          ownActiveGames[metadata.id] = metadata;
        }
        else {
          watchableActiveGames[metadata.id] = metadata;
        }
      });
      UserStore.emitChange();
      break;
    case SiteConstants.GAME_METADATA_UPDATE:
      if(metadata.id in openGames) openGames[metadata.id] = metadata;
      if(metadata.id in activeGames) activeGames[metadata.id] = metadata;
      if(metadata.id in ownOpenGames) ownOpenGames[metadata.id] = metadata;
      if(metadata.id in ownActiveGames) ownActiveGames[metadata.id] = metadata;
      if(metadata.id in joinableOpenGames) joinableOpenGames[metadata.id] = metadata;
      if(metadata.id in watchableOpenGames) watchableOpenGames[metadata.id] = metadata;
      if(metadata.id in watchableActiveGames) watchableActiveGames[metadata.id] = metadata;
      UserStore.emitChange();
      break;
    case SiteConstants.PLAYER_JOINED:
      var players = action.players; //TODO do something with this...
      UserStore.emitChange();
      break;
    case SiteConstants.GAME_JOINED:
      joinedGameAuths[action.gameID] = action.gameAuth;      
      UserStore.emitChange();
    case SiteConstants.HEARTBEAT_FAILED:
      delete joinedGameAuths[action.gameID];      
      UserStore.emitChange();
    default:
      break;
    }
    return true;
  })
});



module.exports = UserStore;
