var keyMirror = require('keymirror');

var ACTIONS = keyMirror({
  GAME_STATE: null,
  GAME_STATE_FAILED: null,
  INITIAL_STATE_FAILED: null,
  GAME_JOINED: null,
  GAME_JOIN_FAILED: null,
  HEARTBEAT_FAILED: null,
  SENT_MOVE_TO_SERVER: null,
  SENT_MOVE_TO_SERVER_FAILED: null,

  DEBUG_SEND_SETUP_SILVER: null,
  DEBUG_SEND_SETUP_GOLD: null,

  GAME_HOVER_SQUARE: null,
  GAME_HOVERED_AWAY: null,
  GAME_CLICK_SQUARE: null,
  GAME_SETUP_GOLD: null,
  GAME_SETUP_SILVER: null,
  GAME_SETUP_OVER: null,
  GAME_SEND_SETUP_GOLD: null,
  GAME_SEND_SETUP_SILVER: null,
  GAME_ADD_STEP: null, //NO LONGER NEEDED, HANDLED IN CLICK_SQUARE
  GAME_UNDO_STEP: null,
  GAME_UNDO_MOVE: null,
  GAME_REDO_STEP: null,
  GAME_REDO_MOVE: null,
  GAME_COMPLETE_MOVE: null,
  GAME_ADD_MOVE: null,
  GAME_FLIP_BOARD: null,
  GAME_SET_COLOR: null,
  GAME_FORFEIT: null
});

const PIECES = {
  EMPTY : 0,
  COLOR : 8,
  COUNT : 15,

  GRABBIT : 1,
  GCAT : 2,
  GDOG : 3,
  GHORSE : 4,
  GCAMEL : 5,
  GELEPHANT : 6,

  SRABBIT : 9,
  SCAT : 10,
  SDOG : 11,
  SHORSE : 12,
  SCAMEL : 13,
  SELEPHANT : 14
};

const GAME = {
  NULL_COLOR: -1,
  GOLD: 0,
  SILVER: 1,
  BOTH_COLOR: 2, //for moving both sides
  NULL_SQUARE_NUM: -1,
  FILES: ['a','b','c','d','e','f','g','h'],
  RANKS: [1,2,3,4,5,6,7,8],
  reverseColor: function(color) {
    return 1-color;
  },

  //TODO are the following even needed???
  pieceSymbol: function(num) {
    return " RCDHMExxrcdhme".charAt(num);
  },
  directionTo: function(fr, to) {
    if(fr === to) return 'x';
    else if (fr - to === 1) return 'w';
    else if (fr - to === -1) return 'e';
    else if (fr - to === 8) return 'n';
    else if (fr - to === -8) return 's';
    return 'x';
  },
  squareName: function(sqNum) {
    var x = sqNum%8;
    var y = Math.floor(sqNum/8);

    const ranks = "87654321";
    const files = "abcdefgh";
    return files.charAt(x)+ranks.charAt(y);
  },
  createStepString: function(pieceNum, frNum, toNum) {
    return this.pieceSymbol(pieceNum) +
           this.squareName(frNum) +
           this.directionTo(frNum, toNum);
  }
};

module.exports = {
  ACTIONS: ACTIONS,
  GAME: GAME,
  PIECES: PIECES
};
