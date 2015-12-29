'use strict';

var assign = require('object-assign');
var EventEmitter = require('events').EventEmitter;
var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');
var Arimaa = require('../lib/arimaa.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var Utils = require('../utils/Utils.js');

const CHANGE_EVENT = 'change';
const MOVE_EVENT = 'new-move';

var debugMsg = "";

var _gameID = null;
var _gameAuth = null;
var _gameState = null;

var _localTimeOffsetFromServer = null;
var _lastStateReceivedTime = null;

var _setupGold   = ['C','D','H','E','M','H','D','C','R','R','R','R','R','R','R','R']; //a2-h2, a1-h1 //default gold setup
var _setupSilver = ['r','r','r','r','r','r','r','r','c','d','h','m','e','h','d','c']; //a8-h8, a7-h7 //default silver setup
var _currentSetup = []; //the current setup the user chooses
var _setupColor = ArimaaConstants.GAME.NULL_COLOR;

var _arimaa = new Arimaa();
var _selSquareNum = ArimaaConstants.GAME.NULL_SQUARE_NUM;
var _selSquareName = "";
var _validSteps = [];
var _selSquareStack = []; //previous selected squares for undo/redo
var _redoSquareStack = []; //used for undo/redo
var _myColor = ArimaaConstants.GAME.NULL_COLOR; //spectators, or before we know what color we are
var _viewSide = ArimaaConstants.GAME.GOLD; //can only be gold or silver (unless we want east/west views?)
var _colorToMove = ArimaaConstants.GAME.NULL_COLOR; //in this context, null color === can't move
var _gameOver = null;
var _sequenceNum = 0;
setInitialState();

const ArimaaStore = Object.assign({}, EventEmitter.prototype, {
  getGameState: function() {
    return _gameState;
  },

  getSetupColor: function() {
    return _setupColor;
  },

  getSetup: function() {
    return _currentSetup;
  },

  getMyColor: function() {
    return _myColor;
  },

  getViewSide: function() {
    return _viewSide;
  },

  getGameOver: function() {
    return _gameOver;
  },

  getDebugMsg: function() {
    return debugMsg;
  },

  getArimaa: function() {
    return _arimaa;
  },

  getBoard: function() {
    console.log('board: ', _arimaa.get_board());
    return _arimaa.get_board();
  },

  getMoveList: function() {
    var moves = _arimaa.get_move_list();
    return moves;
  },

  //player should be "g" or "s"
  //wholeGame specifies whether it should be the time left for just this move or it should be the time on the clock for the whole game
  getClockRemaining: function(player,wholeGame) {
    if(_gameState === null)
      return null;
    if(_gameState.meta.activeGameData === undefined)
      //Doesn't quite work right for the time for the last move
      //return (player == "g") ? Utils.gClockForEndedGame(_gameState) : Utils.sClockForEndedGame(_gameState);
      return null;
    var baseClock = (player == "g") ? _gameState.meta.activeGameData.gClockBeforeTurn : _gameState.meta.activeGameData.sClockBeforeTurn;
    if(_gameState.toMove != player)
      return baseClock;

    var now = Utils.currentTimeSeconds();
    var timeSpent = 0;
    if(_localTimeOffsetFromServer !== null)
      timeSpent = now - _gameState.meta.activeGameData.moveStartTime - _localTimeOffsetFromServer;
    else if(_lastStateReceivedTime !== null)
      timeSpent = now - _lastStateReceivedTime - _gameState.meta.activeGameData.timeSpent;

    var tc = (player == "g") ? _gameState.meta.gTC : _gameState.meta.sTC;
    var clock = Utils.clockAfterTurn(baseClock,timeSpent,Math.floor(_gameState.plyNum / 2),tc);
    if(!wholeGame && tc.maxMoveTime !== undefined)
      clock = Math.min(clock, tc.maxMoveTime - timeSpent);
    return clock;
  },

  getSeletedSquare: function() {
    return {
      num: _selSquareNum,
      name: _selSquareName
    };
  },

  getValidSteps: function() {
    return _validSteps;
  },

  emitChange: function() {
    this.emit(CHANGE_EVENT);
  },

  addChangeListener: function(callback) {
    this.on(CHANGE_EVENT, callback);
  },

  removeChangeListener: function(callback) {
    this.removeListener(CHANGE_EVENT, callback);
  },


  sendMoveToServer: function(gameID,gameAuth,moveStr,plyNum) {
    ArimaaStore.setSelectedSquareToNull();
    APIUtils.sendMove(gameID,gameAuth,moveStr,plyNum,ArimaaStore.sendMoveToServerSuccess,ArimaaStore.sendMoveToServerError);
  },
  sendMoveToServerSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.SENT_MOVE_TO_SERVER,
      data:data
    });
  },
  sendMoveToServerError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.SENT_MOVE_TO_SERVER_FAILED,
      data:data
    });
  },

  setSelectedSquare: function(square) {
    _selSquareNum = square.squareNum;
    _selSquareName = square.squareName;
    _validSteps = _arimaa.generate_steps_for_piece_on_square(_selSquareName);
  },

  setSelectedSquareToNull: function() {
    _selSquareNum = ArimaaConstants.GAME.NULL_SQUARE_NUM; //TODO also move these to a function
    _selSquareName = "";
    _validSteps = [];
  },
  
  dispatcherIndex: ArimaaDispatcher.register(function(action) {

    function _setSelectedSquare(square) {
      ArimaaStore.setSelectedSquare(square);
    }

    function _setSelectedSquareToNull() {
      ArimaaStore.setSelectedSquareToNull();
    }

    switch(action.actionType) {
    case ArimaaConstants.ACTIONS.INITIAL_STATE_FAILED:
      debugMsg = "Failed to get initial game state, try refreshing page: " + action.data;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_JOIN_FAILED:
      debugMsg = "Failed to join game, try refreshing page: " + action.data;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.LOGOUT_FAILED:
      debugMsg = "Failed to heartbeat game, try refreshing page: " + action.data;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.SENT_MOVE_TO_SERVER_FAILED:
      debugMsg = "Failed to send move, try refreshing page: " + action.data;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_STATE_FAILED:
      debugMsg = "Failed to get game state, try refreshing page: " + action.data;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.SENT_MOVE_TO_SERVER:
      debugMsg = "";
      ArimaaStore.emitChange();
      break;

    case ArimaaConstants.ACTIONS.GAME_STATE:
      debugMsg = "";
      _gameState = action.data;

      //Logic for trying to sync up server and local clocks as closely as possible from gameroom clock
      _lastStateReceivedTime = Utils.currentTimeSeconds();
      var crazyTimeSpan = 1200; //20 minutes
      var estimatedTimeOffset = _lastStateReceivedTime - _gameState.meta.now;
      
      //Figure out the offset we are from the server based by taking a min over all of the time offsets we've seen
      //so far, except that if the difference is crazy, then forget history and take the new value
      if(_localTimeOffsetFromServer === null
         || _localTimeOffsetFromServer > estimatedTimeOffset
         || _localTimeOffsetFromServer < estimatedTimeOffset - crazyTimeSpan)
        _localTimeOffsetFromServer = estimatedTimeOffset;

      //Figure out whose turn it is and if the game is over
      if(_gameState.meta.result) {
        _gameOver = _gameState.meta.result;
        _colorToMove = ArimaaConstants.GAME.NULL_COLOR;
      }
      else if(_gameState.meta.numPly % 2 === 0)
        _colorToMove = ArimaaConstants.GAME.GOLD;
      else
        _colorToMove = ArimaaConstants.GAME.SILVER;

      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_JOINED:
      _gameID = action.gameID;
      _gameAuth = action.gameAuth;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_SET_COLOR:
      _myColor = action.color;
      if(_myColor === ArimaaConstants.GAME.SILVER) {
        _viewSide = ArimaaConstants.GAME.SILVER;
      } else {
        _viewSide = ArimaaConstants.GAME.GOLD;
      }
      ArimaaStore.emitChange();
      break;
      //used after getting game status from server
      //to signal we should enter a setup
    case ArimaaConstants.ACTIONS.GAME_SETUP_GOLD:
      //we only show the pieces for setup when its our turn to setup
      if(_myColor === ArimaaConstants.GAME.GOLD) {
        _currentSetup = _setupGold;
        _setupColor = ArimaaConstants.GAME.GOLD;
      }
      ArimaaStore.emitChange();
      break;
      //also used after getting status from server
    case ArimaaConstants.ACTIONS.GAME_SETUP_SILVER:
      //we only show the pieces for setup when its our turn to setup,
      if(_myColor === ArimaaConstants.GAME.SILVER) {
        _currentSetup = _setupSilver;
        _setupColor = ArimaaConstants.GAME.SILVER;
      }
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_SETUP_OVER:
      _setupColor = ArimaaConstants.GAME.NULL_COLOR;
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_SEND_SETUP_GOLD:
      var moveStr = "";
      for(var i=0;i<2;i++) {
        for(var j=0;j<8;j++) {
          moveStr += _currentSetup[8*i+j]+ArimaaConstants.GAME.FILES[j]+(2-i).toString()+" ";
        }
      }
      _arimaa.setup_gold(moveStr); //NO ERROR CHECKING YET
      ArimaaStore.sendMoveToServer(action.gameID, _gameAuth, moveStr, 0);
      break;
    case ArimaaConstants.ACTIONS.GAME_SEND_SETUP_SILVER:
      var moveStr = "";
      for(var i=0;i<2;i++) {
        for(var j=0;j<8;j++) {
          moveStr += _currentSetup[8*i+j]+ArimaaConstants.GAME.FILES[j]+(8-i).toString()+" ";
        }
      }
      _arimaa.setup_silver(moveStr); //NO ERROR CHECKING YET
      ArimaaStore.sendMoveToServer(action.gameID, _gameAuth, moveStr, 1);
      break;

      //debug methods to send setup as text
      //only used in debug component
    case ArimaaConstants.ACTIONS.DEBUG_SEND_SETUP_GOLD:
      _arimaa.setup_gold(action.text);
      ArimaaStore.sendMoveToServer(action.gameID, _gameAuth, action.text, 0);
      ArimaaStore.emitChange();
      //usually, this is done with the game_setup_silver action,
      //but for local games where we don't go through the network
      //we do this here
      _currentSetup = _silverSetup;

      break;
    case ArimaaConstants.ACTIONS.DEBUG_SEND_SETUP_SILVER:
      _arimaa.setup_silver(action.text);
      ArimaaStore.sendMoveToServer(action.gameID, _gameAuth, action.text, 1);
      ArimaaStore.emitChange();
      break;

    case ArimaaConstants.ACTIONS.GAME_HOVER_SQUARE:
      //Do nothing unless we're one of the players AND it's our turn
      if(_myColor === ArimaaConstants.GAME.NULL_COLOR || _myColor !== _colorToMove)
        break;
      //Do nothing unless we're in hover-click mode
      if(Utils.getSetting(SiteConstants.SETTINGS.MOVEMENT_MODE_KEY, SiteConstants.SETTINGS.MOVEMENT_MODE.DEFAULT) !== SiteConstants.SETTINGS.MOVEMENT_MODE.HOVERCLICK)
        break;

      //Only act outside of the setup
      if(_setupColor === ArimaaConstants.GAME.NULL_COLOR) {
        if(!_arimaa.is_empty(action.squareName)) {
          _setSelectedSquare(action);
          ArimaaStore.emitChange();
        }        
      }
      break;

    case ArimaaConstants.ACTIONS.GAME_HOVERED_AWAY:
      //Do nothing unless we're one of the players AND it's our turn
      if(_myColor === ArimaaConstants.GAME.NULL_COLOR || _myColor !== _colorToMove)
        break;

      //Do nothing unless we're in hover-click mode
      if(Utils.getSetting(SiteConstants.SETTINGS.MOVEMENT_MODE_KEY, SiteConstants.SETTINGS.MOVEMENT_MODE.DEFAULT) !== SiteConstants.SETTINGS.MOVEMENT_MODE.HOVERCLICK)
        break;
      //Only act outside of the setup
      if(_setupColor === ArimaaConstants.GAME.NULL_COLOR) {
        _setSelectedSquareToNull();
        ArimaaStore.emitChange();
      }
      break;
      
    case ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE:
      //Do nothing unless we're one of the players AND it's our turn
      if(_myColor === ArimaaConstants.GAME.NULL_COLOR || _myColor !== _colorToMove)
        break;
      
      //GOLD SETUP----------------------------------------------------------------
      if(_setupColor === ArimaaConstants.GAME.GOLD) {
        if(action.squareNum < 48) { //TODO ideally, we wouldn't use these magic numbers
          _setSelectedSquareToNull();
        } else if(_selSquareNum === ArimaaConstants.GAME.NULL_SQUARE_NUM) {
          _setSelectedSquare(action);
        } else {
          var temp = _currentSetup[action.squareNum-48];
          _currentSetup[action.squareNum-48] = _currentSetup[_selSquareNum-48];
          _currentSetup[_selSquareNum-48] = temp;
          _setSelectedSquareToNull();
        }
      }
      //SILVER SETUP---------------------------------------------------------------
      else if(_setupColor === ArimaaConstants.GAME.SILVER) {
        if(action.squareNum > 16) {
          _setSelectedSquareToNull();
        } else if(_selSquareNum === ArimaaConstants.GAME.NULL_SQUARE_NUM) {
          _setSelectedSquare(action);
        } else {
          var temp = _currentSetup[action.squareNum];
          _currentSetup[action.squareNum] = _currentSetup[_selSquareNum];
          _currentSetup[_selSquareNum] = temp;
          _setSelectedSquareToNull();
        }
      }
      //REGULAR GAME---------------------------------------------------------------
      else {      
        //TODO USE IF_EMPTY FUNCTION AFTER UPDATING ARIMAAJS
        if (_selSquareNum === action.squareNum) {
          //Deselect the current square if we clicked it again and we're in click-click mode
          if(Utils.getSetting(SiteConstants.SETTINGS.MOVEMENT_MODE_KEY, SiteConstants.SETTINGS.MOVEMENT_MODE.DEFAULT) !== SiteConstants.SETTINGS.MOVEMENT_MODE.CLICKCLICK)
            _setSelectedSquareToNull();
        }
        else if(!_arimaa.is_empty(action.squareName)) {
          _setSelectedSquare(action);
        }
        else if(_selSquareNum !== ArimaaConstants.GAME.NULL_SQUARE_NUM) {
          var stepToAdd = null;
          _validSteps.forEach(function(s) {
            if(s.destSquare === action.squareName) stepToAdd = s;
          });
          if(stepToAdd) {
            var k = _arimaa.add_step(stepToAdd.string);
            _redoSquareStack = []; //can't redo after adding a new step
            //TODO USE if_empty function after updating arimaajs!!!!
            //Handle the case where the piece disappears due to a sacrifice
            if(!_arimaa.is_empty(stepToAdd.destSquare)) {
              _selSquareStack.push({squareNum:_selSquareNum,squareName:_selSquareName});
              _setSelectedSquare(action);
            }
            else {
              _setSelectedSquareToNull();
            }
          }
          else {
            _setSelectedSquareToNull();
          }
        }
      }
      ArimaaStore.emitChange();
      break;


    case ArimaaConstants.ACTIONS.GAME_UNDO_STEP:
      var s = _selSquareStack.pop();
      _arimaa.undo_step(); //check to see if we have a step to undo
      if(s) {
        _setSelectedSquare(s);
        _redoSquareStack.push(s);
      }
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_REDO_STEP:
      var s = _redoSquareStack.pop();
      if(s) {
        _arimaa.redo_step();
        _setSelectedSquare(s);
        _undoStepStack.push(s);
      }
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_REDO_MOVE:
      break;
    case ArimaaConstants.ACTIONS.GAME_ADD_MOVE:
      _arimaa.undo_ongoing_move();
      var moveStr = action.move;
      _redoSquareStack = [];
      _selSquareStack = [];
      _setSelectedSquareToNull();
      _arimaa.add_move_string(moveStr);
      var completed = _arimaa.complete_move();
      if(!completed.success) {
        debugMsg = completed.reason;
        _setSelectedSquareToNull();
        ArimaaStore.emitChange();
      }
      break;
    case ArimaaConstants.ACTIONS.GAME_COMPLETE_MOVE:
      var completed = _arimaa.complete_move();
      if(completed.success) {
        //definitely need a better way of doing this...
        //converts list of step strings to single move string
        var moves = _arimaa.get_move_list();
        var lastMove = moves[moves.length-1];
        var lastMoveStr = lastMove.map(function(s) {return s.string;}).join(' ');

        //send move to server
        ArimaaStore.sendMoveToServer(action.gameID, _gameAuth, lastMoveStr, _arimaa.get_halfmove_number()+1);
        _redoSquareStack = [];
        _selSquareStack = [];
      } else {
        //alert "can't complete move because..."
        //undo step?
        debugMsg = completed.reason;
      }
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_FLIP_BOARD:
      _viewSide = ArimaaConstants.GAME.reverseColor(_viewSide);
      _setSelectedSquareToNull();
      ArimaaStore.emitChange();
      break;
    default:
      break;
    }
    return true; // No errors. Needed by promise in Dispatcher.
  })

});

function setInitialState() {
  _arimaa = new Arimaa();
  _currentSetup = _setupGold;
}

module.exports = ArimaaStore;
