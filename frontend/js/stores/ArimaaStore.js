'use strict'

var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var EventEmitter = require('events').EventEmitter;
var ArimaaConstants = require('../constants/ArimaaConstants.js');
var assign = require('object-assign');
var Arimaa = require('../lib/arimaa.js');

const CHANGE_EVENT = 'change';
const MOVE_EVENT = 'new-move';

const NULL_SQUARE_NUM = -1;

var debugMsg = "";

var _arimaa = new Arimaa();
var _selSquareNum = NULL_SQUARE_NUM;
var _selSquareName = "";
var _validSteps = [];
var _selSquareStack = []; //previous selected squares for undo/redo
var _redoSquareStack = []; //used for undo/redo

setInitialState();

const ArimaaStore = Object.assign({}, EventEmitter.prototype, {
  getDebugMsg: function() {
    return debugMsg;
  },

  getArimaa: function() {
    return _arimaa;
  },

  getBoard: function() {
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
      _selSquareNum = NULL_SQUARE_NUM; //also move these to a function
      _selSquareName = "";
      _validSteps = [];
    }

    switch(action.actionType) {
      case ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE:
        //DEFINITELY NEED TO SIMPLIFY THESE CONDITIONALS

        //USE IF_EMPTY FUNCTION AFTER UPDATING ARIMAAJS
        if(_selSquareNum === NULL_SQUARE_NUM && !_arimaa.is_empty(action.squareName)) {
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
        var stepsList = moveStr.split(' ');
        console.log(stepsList);
        _redoSquareStack = [];
        _selSquareStack = [];
        _setSelectedSquareToNull();
        _arimaa.add_move(stepsList);
        var completed = _arimaa.complete_move();
        if(!completed.success) {
          debugMsg = completed.reason;
          ArimaaStore.emitChange();
        }

      case ArimaaConstants.ACTIONS.GAME_COMPLETE_MOVE:

        var completed = _arimaa.complete_move();
        if(completed.success) {

          //send move to server
          //wait for response
          //blah blah blah
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
      default:
        break;
    }
    return true; // No errors. Needed by promise in Dispatcher.
  })

});

function setInitialState() {
  _arimaa = new Arimaa({fen:"8/8/3CR3/3r4/8/8/8/8"});
  //
  _arimaa.add_step("Cd6n");
  _arimaa.complete_move();
}

module.exports = ArimaaStore;
