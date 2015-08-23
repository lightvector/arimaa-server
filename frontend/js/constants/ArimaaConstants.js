var keyMirror = require('keymirror');


var ACTION_CONSTANTS = keyMirror({
  GAME_CLICK_SQUARE: null,
  GAME_ADD_STEP: null, //NO LONGER NEEDED, HANDLED IN CLICK_SQUARE
  GAME_UNDO_STEP: null,
  GAME_UNDO_MOVE: null,
  GAME_REDO_STEP: null,
  GAME_REDO_MOVE: null,
  GAME_COMPLETE_MOVE: null,
  GAME_ADD_MOVE: null,
  GAME_FORFEIT: null
});

var GAME_CONSTANTS = {
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
}

module.exports = {
  ACTIONS: ACTION_CONSTANTS,
  GAME: GAME_CONSTANTS
}
