'use strict';

var assign = require('object-assign');
var EventEmitter = require('events').EventEmitter;
var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
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

//TODO: remove these square nums since they're inconsistent with the arimaajs internal square numbers
//actually the arimaajs square numbers shouldn't be used outside of arimaajs, so
//switch to only using square names for equality checks, etc
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

  getOngoingMove: function() {
    return _arimaa.get_ongoing_move_string();
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

  dispatcherIndex: ArimaaDispatcher.register(function(action) {

    function _setSelectedSquare(square) {
      _selSquareNum = square.squareNum;
      _selSquareName = square.squareName;
      _validSteps = _arimaa.generate_steps_for_piece_on_square(_selSquareName);
    }

    function _setSelectedSquareToNull() {
      _selSquareNum = ArimaaConstants.GAME.NULL_SQUARE_NUM; //also move these to a function
      _selSquareName = "";
      _validSteps = [];
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

      _lastStateReceivedTime = Utils.currentTimeSeconds();
      var crazyTimeSpan = 1200; //20 minutes
      var estimatedTimeOffset = _lastStateReceivedTime - _gameState.meta.now;
      if(_localTimeOffsetFromServer === null
         || _localTimeOffsetFromServer > estimatedTimeOffset
         || _localTimeOffsetFromServer < estimatedTimeOffset - crazyTimeSpan)
        _localTimeOffsetFromServer = estimatedTimeOffset;

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
    case ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE_SETUP:
      //there's probably a way to use bitwise operations
      //to simplify these conditionals, but this will work
      if(_setupColor === ArimaaConstants.GAME.GOLD) { //gold to setup
        if(action.squareNum < 48) { //ideally, we wouldn't use theses magic numbers
          _setSelectedSquareToNull();
        } else if(_selSquareNum === ArimaaConstants.GAME.NULL_SQUARE_NUM) {
          _setSelectedSquare(action);
        } else {
          var temp = _currentSetup[action.squareNum-48];
          _currentSetup[action.squareNum-48] = _currentSetup[_selSquareNum-48];
          _currentSetup[_selSquareNum-48] = temp;
          _setSelectedSquareToNull();
        }
      } else { //silver to setup
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
      ArimaaStore.emitChange();
      break;

    case ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE:
      //DEFINITELY NEED TO SIMPLIFY THESE CONDITIONALS

      //USE IF_EMPTY FUNCTION AFTER UPDATING ARIMAAJS
      if(_selSquareNum === ArimaaConstants.GAME.NULL_SQUARE_NUM && !_arimaa.is_empty(action.squareName)) {
        _setSelectedSquare(action);
      } else if (_selSquareNum === action.squareNum) {
        _setSelectedSquareToNull();
      } else {
        var stepToAdd = null;
        _validSteps.forEach(function(s) {
          if(s.destSquare === action.squareName) stepToAdd = s;
        });
        if(stepToAdd) {
          var k = _arimaa.add_step(stepToAdd.string);
          _redoSquareStack = []; //can't redo after adding a new step
          //console.log(k);
          //USE if_empty function after updating arimaajs!!!!
          //used if we move into a trap
          if(!_arimaa.is_empty(stepToAdd.destSquare)) {
            _selSquareStack.push({squareNum:_selSquareNum,squareName:_selSquareName});
            _setSelectedSquare(action);
          } else {
            _setSelectedSquareToNull();
          }
        } else {
          if(!_arimaa.is_empty(action.squareName)) {
            _setSelectedSquare(action);
          } else {
            _setSelectedSquareToNull();
          }
        }
      }
      ArimaaStore.emitChange();
      break;


    case ArimaaConstants.ACTIONS.GAME_UNDO_STEP:
      var undo = _arimaa.undo_step();
      if(undo) {
        _setSelectedSquare({squareNum:undo.squareNum,squareName:undo.square});
        ArimaaStore.emitChange();
      }

      /*
      var s = _selSquareStack.pop();
      _arimaa.undo_step(); //check to see if we have a step to undo
      if(s) {
        _setSelectedSquare(s);
        _redoSquareStack.push(s);
      }
      ArimaaStore.emitChange();*/
      break;
    case ArimaaConstants.ACTIONS.GAME_REDO_STEP:
      var redo = _arimaa.redo_step();
      if(redo) {
        _setSelectedSquare({squareNum:redo.destSquareNum,squareName:redo.destSquare});
        ArimaaStore.emitChange();
      }
      /*
      var s = _redoSquareStack.pop();
      console.log('redo');
      console.log(s);
      console.log(_redoSquareStack);
      if(s) {
        _arimaa.redo_step();
        _setSelectedSquare(s);
        //_undoStepStack.push(s);
      }*/
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

        if(completed.victory.result !== 0) {
          console.log(completed.victory.result);
          var winner = "";
          if(completed.victory.result === 1) {
            //maybe there should be a better check since _myColor can be null
            //but if we're completing a move, we should always have a color
            winner = (_myColor === ArimaaConstants.GAME.GOLD) ? 'g' : 's';
          } else if(completed.victory.result === -1) {
            //there's probably a way to combine the two statements better
            winner = (_myColor === ArimaaConstants.GAME.GOLD) ? 's' : 'g';
          }
          _gameOver = {winner:winner, reason:completed.victory.reason};
        }

        //send move to server
        ArimaaStore.sendMoveToServer(action.gameID, _gameAuth, lastMoveStr, _arimaa.get_halfmove_number()+1);
        _redoSquareStack = [];
        _selSquareStack = [];
        _setSelectedSquareToNull();
      } else {
        //alert "can't complete move because..."
        //undo step?
        debugMsg = completed.reason;
      }
      ArimaaStore.emitChange();
      break;
    case ArimaaConstants.ACTIONS.GAME_FLIP_BOARD:
      _viewSide = ArimaaConstants.GAME.reverseColor(_viewSide);
      ArimaaStore.emitChange();
    case ArimaaConstants.ACTIONS.GAME_OVER:
      _gameOver = action.result;
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
