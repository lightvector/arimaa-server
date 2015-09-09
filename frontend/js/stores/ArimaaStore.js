'use strict'

var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var EventEmitter = require('events').EventEmitter;
var ArimaaConstants = require('../constants/ArimaaConstants.js');
var SiteConstants = require('../constants/SiteConstants.js');
var assign = require('object-assign');
var Arimaa = require('../lib/arimaa.js');
var APIUtils = require('../utils/WebAPIUtils.js');

const CHANGE_EVENT = 'change';
const MOVE_EVENT = 'new-move';

var debugMsg = "";

var _gameID;
var _gameAuth;

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
  getMyColor: function() {
    return _myColor;
  },

  getViewSide: function() {
    return _viewSide;
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

  getSeletedSquare: function() {
    return {
      num: _selSquareNum,
      name: _selSquareName
    }
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
      //maybe we should put this in user store? or both???
      case SiteConstants.GAME_CREATED:
        _gameID = action.gameId;
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
      case ArimaaConstants.ACTIONS.GAME_SETUP_GOLD:
        _arimaa.setup_gold(action.text);
        APIUtils.send_move(action.gameID, action.text, 0, function(){}, function(){});
        ArimaaStore.emitChange();
        break;
      case ArimaaConstants.ACTIONS.GAME_SETUP_SILVER:
        _arimaa.setup_silver(action.text);
        APIUtils.send_move(action.gameID, action.text, 1, function(){}, function(){});
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
          ArimaaStore.emitChange();
        }

      case ArimaaConstants.ACTIONS.GAME_COMPLETE_MOVE:
        var completed = _arimaa.complete_move();
        if(completed.success) {
          //definitely need a better way of doing this...
          var moves = _arimaa.get_move_list();
          var lastMove = moves[moves.length-1];
          var lastMoveStr = lastMove.map(function(s) {return s.string;}).join(' ');

          //send move to server
          APIUtils.send_move(action.gameID, lastMoveStr, _arimaa.get_halfmove_number()+1, function(){}, function(){});
          _redoSquareStack = [];
          _selSquareStack = [];
          _setSelectedSquareToNull();
        } else {
          //alert "can't complete move because..."
          //undo step
          debugMsg = completed.reason;
        }
        ArimaaStore.emitChange();
        break;
      case ArimaaConstants.ACTIONS.GAME_FLIP_BOARD:
        _viewSide = ArimaaConstants.GAME.reverseColor(_viewSide);
        ArimaaStore.emitChange();
      default:
        break;
    }
    return true; // No errors. Needed by promise in Dispatcher.
  })

});

//debug only???
function setInitialState() {
  var options = {fen:"8/8/3CR3/3r4/8/8/8/8"};
  _arimaa = new Arimaa();
  //
  //_arimaa.add_step("Cd6n");
  //_arimaa.complete_move();
}

module.exports = ArimaaStore;
