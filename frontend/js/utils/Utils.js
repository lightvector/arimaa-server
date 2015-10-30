
//Some miscellaneous useful functions

//Convert number of seconds into time span string
var Utils = {
  gameSpanString: function(seconds) {
    var s = "";
    if(seconds >= 86400) { s += Math.floor(seconds/86400) + "d"; seconds = seconds % 86400;}
    if(seconds >= 3600)  { s += Math.floor(seconds/3600)  + "h"; seconds = seconds % 3600;}
    if(seconds >= 60)    { s += Math.floor(seconds/60)    + "m"; seconds = seconds % 60;}
    if(seconds >= 0.5)   { s += Math.round(seconds)       + "s"; seconds = 0;}
    return s;
  },

  //Convert time control json object into string
  gameTCString: function(tc) {
    var s = "";
    s += Utils.gameSpanString(tc.initialTime);
    if(tc.increment !== undefined && tc.increment > 0) s += "+" + Utils.gameSpanString(tc.increment);
    if(tc.delay !== undefined && tc.delay > 0) s += "=" + Utils.gameSpanString(tc.delay);
    if(tc.maxReserve !== undefined) s += "(" + Utils.gameSpanString(tc.maxReserve) + ")";
    if(tc.maxMoveTime !== undefined) s += "(" + Utils.gameSpanString(tc.maxMaxMoveTime) + " max/mv)";
    if(tc.overtimeAfter !== undefined) s += "(max " + tc.overtimeAfter + "t)";
    return s;
  }
}

module.exports = Utils;
