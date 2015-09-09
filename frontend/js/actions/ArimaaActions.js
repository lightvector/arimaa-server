var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var UserStore = require('../stores/UserStore.js');

const FUNC_NOP = function(){}

var ArimaaActions = {
  //we shouldn't
  gameStatus: function(gameID, sequence) {
    APIUtils.gameStatus(gameID, sequence, ArimaaActions.gameStatusSuccess, FUNC_NOP);
  },

  gameStatusSuccess: function(data) {
    var history = data.history;
    //maybe do a while loop here?
    var n = ArimaaStore.getMoveList().length;
    while(history.length > ArimaaStore.getMoveList().length) {
        ArimaaActions.addMove(history[n]);
        n++;
    }
    if(data.openGameData) {
      //game is still unstarted
    }
    if(data.meta) {
      if(data.meta.gUser.name === UserStore.getUsername() && ArimaaStore.getMyColor() != ArimaaConstants.GAME.GOLD) {
        ArimaaDispatcher.dispatch({
          actionType: ArimaaConstants.ACTIONS.GAME_SET_COLOR,
          color: ArimaaConstants.GAME.GOLD
        });
      } else if(data.meta.sUser.name === UserStore.getUsername() && ArimaaStore.getMyColor() != ArimaaConstants.GAME.SILVER) {
        //dispatch color silver
        ArimaaDispatcher.dispatch({
          actionType: ArimaaConstants.ACTIONS.GAME_SET_COLOR,
          color: ArimaaConstants.GAME.SILVER
        });
      }
    }
    //if(data.)


  },

  clickSquare: function(sqNum, sqName) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE,
      squareNum: sqNum,
      squareName: sqName
    });
  },

  undoStep: function() {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_UNDO_STEP
    });
  },

  redoStep: function() {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_REDO_STEP
    });
  },

  setupGold: function(gameID, setupString) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_SETUP_GOLD,
      text: setupString,
      gameID: gameID
    });
  },

  setupSilver: function(gameID, setupString) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_SETUP_SILVER,
      text: setupString,
      gameID: gameID
    });
  },

  addStep: function(stepString) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_ADD_STEP,
      text: stepString
    });
  },

  //from opponents
  addMove: function(moveStr) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_ADD_MOVE,
      move: moveStr
    });
  },

  completeMove: function(gameID) {
    //delete following line
    //APIUtils.send_move(gameID, moveStr, plyNum, FUNC_NOP, FUNC_NOP);
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_COMPLETE_MOVE,
      gameID: gameID
    });
  },

  flipBoard: function() {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_FLIP_BOARD
    })
  },


  viewPrevBoardState: function(plyNum) {

  }

};

module.exports = ArimaaActions;
