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

var joinedGameAuths = {};      //GameAuths for games that we've joined
var leftCreatedGameIDs = {}; //GameIDs for games that we've left that we created

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
  emitNewOpenJoinedGame: function(gameID) {this.emit(NEW_OPEN_JOINED_GAME, gameID);},

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

  removeGame: function(gameID) {
    if(gameID in openGames) delete openGames[gameID];
    if(gameID in activeGames) delete activeGames[gameID];
    if(gameID in ownOpenGames) delete ownOpenGames[gameID];
    if(gameID in ownActiveGames) delete ownActiveGames[gameID];
    if(gameID in joinableOpenGames) delete joinableOpenGames[gameID];
    if(gameID in watchableOpenGames) delete watchableOpenGames[gameID];
    if(gameID in watchableActiveGames) delete watchableActiveGames[gameID];
  },

  addGame: function(metadata) {
    var username = UserStore.getUsername();
    var gameID = metadata.gameID;
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
      ownOpenGames = {};
      joinableOpenGames = {};
      watchableOpenGames = {};
      var newOpenJoinedGames = [];
      action.metadatas.forEach(function(metadata){
        //Keep the highest sequence number
        if(metadata.gameID in oldOpenGames && oldOpenGames[metadata.gameID].sequence > metadata.sequence)
          metadata = oldOpenGames[metadata.gameID];

        UserStore.addGame(metadata);

        //If this is the first time we've seen this game, and we're joined to the game, record it so
        //that we can report the event
        if(!(metadata.gameID in oldOpenGames)) {
          var joined = false;
          for(var j = 0; j<metadata.openGameData.joined.length; j++) {
            if(metadata.openGameData.joined[j].name == username) {
              joined = true; break;
            }
          }
          if(joined)
            newOpenJoinedGames.push(metadata);
        }
      });
      UserStore.emitChange();
      for(var i = 0; i < newOpenJoinedGames.length; i++) {
        UserStore.emitNewOpenJoinedGame(newOpenJoinedGames[i].gameID);
      }
      break;
    case SiteConstants.ACTIVE_GAMES_LIST:
      var oldActiveGames = activeGames;
      activeGames = {};
      ownActiveGames = {};
      watchableActiveGames = {};
      action.metadatas.forEach(function(metadata){
        //Keep the highest sequence number
        if(metadata.gameID in oldActiveGames && oldActiveGames[metadata.gameID].sequence > metadata.sequence)
          metadata = oldActiveGames[metadata.gameID];

        UserStore.addGame(metadata);
      });
      UserStore.emitChange();
      break;
    case SiteConstants.GAME_METADATA_UPDATE:
      var metadata = action.metadata;
      //Keep the highest sequence number
      if(metadata.gameID in openGames && openGames[metadata.gameID].sequence > metadata.sequence)
        break;
      else if(metadata.gameID in activeGames && activeGames[metadata.gameID].sequence > metadata.sequence)
        break;

      UserStore.removeGame(metadata.gameID);
      UserStore.addGame(metadata);
      UserStore.emitChange();
      break;
    case SiteConstants.PLAYER_JOINED:
      var players = action.players; //TODO do something with this...
      UserStore.emitChange();
      break;
    case SiteConstants.GAME_JOINED:
      joinedGameAuths[action.gameID] = action.gameAuth;
      UserStore.emitChange();
      break;
    case SiteConstants.HEARTBEAT_FAILED:
      delete joinedGameAuths[action.gameID];
      UserStore.emitChange();
      break;
    case SiteConstants.LEAVE_GAME_SUCCESS:
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
