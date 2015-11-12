var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var UserStore = require('../stores/UserStore.js');

const FUNC_NOP = function(){};

//Actions for the game client
var ArimaaActions = {

  //Fire off the initial game state query to get the state to begin with
  //This begins the whole set of update and heartbeat loops and such
  startAllLoops: function(gameID) {
    APIUtils.gameState(gameID, 0, ArimaaActions.initialStateSuccess, ArimaaActions.initialStateError);
  },
  initialStateSuccess: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_STATE,
      data:data
    });
    //Check if we're part of this game. If so, then join the game and begin heartbeating
    var username = UserStore.getUsername();
    if((data.meta.gUser !== undefined && data.meta.gUser.name == username) ||
       (data.meta.sUser !== undefined && data.meta.sUser.name == username)) {
      ArimaaActions.joinAndStartHeartbeatLoop(data.meta.gameID);
    }
    //Regardless of whether we're a player in this game or not, begin state update loop
    ArimaaActions.startGameStateLoop();
  },
  initialStateError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.INITIAL_STATE_FAILED,
      data: data
    });
  },

  //Join game and heartbeat it in a loop
  joinAndStartHeartbeatLoop: function(gameID) {
    APIUtils.joinGame(gameID,
                      function(data) {ArimaaActions.joinGameSuccess(gameID,data);},
                      function(data) {ArimaaActions.joinGameError(gameID,data);});
  },
  joinGameSuccess: function(gameID, data) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_JOINED,
      gameID: gameID,
      gameAuth: data.gameAuth
    });
    ArimaaActions.startHeartbeatLoop(gameID,data.gameAuth);
  },
  joinGameError: function(gameID, data) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_JOIN_FAILED,
      gameID: gameID,
      data: data
    });
  },

  //Initiates a loop heartbeating this game so long as this game is active and we're joined with it
  startHeartbeatLoop: function(gameID, gameAuth) {
    var username = UserStore.getUsername();
    var game = ArimaaStore.getGameState();

    //If the game is not active, terminate
    if(game.meta.activeGameData === undefined)
      return;

    //If we're not joined to this game, terminate.
    if((game.meta.gUser.name == username && !game.meta.activeGameData.gPresent) ||
       (game.meta.sUser.name == username && !game.meta.activeGameData.sPresent))
      return;

    //TODO timeout
    APIUtils.gameHeartbeat(gameID, gameAuth, FUNC_NOP, function (data) {ArimaaActions.onHeartbeatError(gameID,data);});
     setTimeout(function () {ArimaaActions.startHeartbeatLoop(gameID, gameAuth);}, 5000);
  },
  onHeartbeatError: function(gameID,data) {
    //TODO
    console.log(data);
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.HEARTBEAT_FAILED,
      gameID: gameID
    });
  },

  //Initiates a loop querying for the game state so long as the game is open or active.
  startGameStateLoop: function() {
    var game = ArimaaStore.getGameState();
    //If the game is not open or active, terminate
    if(game.meta.openGameData === undefined && game.meta.activeGameData === undefined)
      return;
    APIUtils.gameState(game.meta.gameID, game.meta.sequence+1, ArimaaActions.gameStateSuccess, ArimaaActions.gameStateError);
  },
  gameStateSuccess: function(data) {
    //TODO delete all the other stuff and just leave this action
    //TODO why do we even need a dispatcher? Why not call methods directly on the ArimaaStore?
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_STATE,
      data:data
    });
    var history = data.history;
    var n = ArimaaStore.getMoveList().length;
    while(history.length > ArimaaStore.getMoveList().length && n < history.length) {
        ArimaaActions.addMove(history[n]);
        n++;
    }
    if(data.openGameData) {
      //game is still unstarted
    }
    else if(data.meta) { //we can have a meta w/o gUser/sUser if they haven't been accepted
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
    if(history.length === 0) {
      ArimaaDispatcher.dispatch({
        actionType: ArimaaConstants.ACTIONS.GAME_SETUP_GOLD
      });
    } else if(history.length === 1) {
      ArimaaDispatcher.dispatch({
        actionType: ArimaaConstants.ACTIONS.GAME_SETUP_SILVER
      });
    } else {
      ArimaaDispatcher.dispatch({
        actionType: ArimaaConstants.ACTIONS.GAME_SETUP_OVER
      });
    }

    if(data.result) {
      ArimaaDispatcher.dispatch({
        actionType: ArimaaConstants.ACTIONS.GAME_OVER,
        result: data.result
      });
    }

    //TODO sleep 200ms
    setTimeout(ArimaaActions.startGameStateLoop, 200);
  },
  gameStateError: function(gameID,gameAuth,data) {
    //TODO should there be a way to detect if the site is down?
    console.log(data);
    //TODO sleep 2000 ms
    setTimeout(ArimaaActions.startGameStateLoop, 2000);
  },


  clickSquare: function(sqNum, sqName) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE,
      squareNum: sqNum,
      squareName: sqName
    });
  },

  clickSquareSetup: function(sqNum, sqName) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_CLICK_SQUARE_SETUP,
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

  debugSendGoldSetup: function(gameID, setupString) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.DEBUG_SEND_SETUP_GOLD,
      text: setupString,
      gameID: gameID
    });
  },

  debugSendSilverSetup: function(gameID, setupString) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.DEBUG_SEND_SETUP_SILVER,
      text: setupString,
      gameID: gameID
    });
  },

  sendGoldSetup: function(gameID) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_SEND_SETUP_GOLD,
      gameID: gameID
    });
  },

  sendSilverSetup: function(gameID) {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_SEND_SETUP_SILVER,
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
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_COMPLETE_MOVE,
      gameID: gameID
    });
  },

  flipBoard: function() {
    ArimaaDispatcher.dispatch({
      actionType: ArimaaConstants.ACTIONS.GAME_FLIP_BOARD
    });
  },


  viewPrevBoardState: function(plyNum) {

  }

};

module.exports = ArimaaActions;
