
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
  },

  clockAfterTurn: function(clockBeforeTurn, timeSpent, turn, tc){
    var overtimeFactor = 1.0;
    var overtimeFactorPerTurn = 1.0 - 1.0 / 30.0;
    if(tc.overtimeAfter !== undefined && turn >= tc.overtimeAfter)
      overtimeFactor = Math.pow(overtimeFactorPerTurn, turn - tc.overtimeAfter + 1);
    var adjIncrement = (tc.increment === undefined ? 0 : tc.increment) * overtimeFactor;
    var adjDelay = (tc.increment === undefined ? 0 : tc.delay) * overtimeFactor;
    var clock = clockBeforeTurn + adjIncrement - Math.max(timeSpent - adjDelay, 0.0);
    if(tc.maxReserve !== undefined)
       clock = Math.min(clock, tc.maxReserve);
    return clock;
  },

  gClockForEndedGame: function(gameState) {
    var tc = gameState.meta.gTC;
    var clock = tc.initialTime;
    for(var i = 0; i < gameState.moveTimes.length; i += 2) {
      var timeSpent = gameState.moveTimes[i].time - gameState.moveTimes[i].start;
      clock = Utils.clockAfterTurn(clock,timeSpent,i/2,tc);
    }
    return clock;
  },

  sClockForEndedGame: function(gameState) {
    var tc = gameState.meta.sTC;
    var clock = tc.initialTime;
    for(var i = 1; i < gameState.moveTimes.length; i += 2) {
      var timeSpent = gameState.moveTimes[i].time - gameState.moveTimes[i].start;
      clock = Utils.clockAfterTurn(clock,timeSpent,(i-1)/2,tc);
    }
    return clock;
  },

  currentTimeSeconds: function() {
    if (!Date.now)
      return new Date().getTime() / 1000.;
    return Date.now() / 1000.;
  },

  timeSpanToString: function(span) {
    var prefix = "";
    if(span < 0) {
      prefix = "-";
      span = -span;
    }

    var days    = Math.floor(span / 86400);
    var hours   = Math.floor(span / 3600 - (days * 86400));
    var minutes = Math.floor((span - (days * 86400) - (hours * 3600)) / 60);
    var seconds = Math.floor(span - (days * 86400) - (hours * 3600) - (minutes * 60));

    var dstr = ""+days;
    var hstr = ""+hours;
    var mstr = ""+minutes;
    var sstr = ""+seconds;

    if (sstr < 10) {sstr = "0"+sstr;}

    if(days > 0 || hours > 0) {
      if (mstr < 10) {mstr = "0"+mstr;}
    }
    if(days > 0) {
      if (hstr < 10) {hstr = "0"+hstr;}
    }

    if(days > 0)
      return prefix + dstr + "d " + hstr + ":" + mstr + ":" + sstr;
    if(hours > 0)
      return prefix + hstr + ":" + mstr + ":" + sstr;

    return prefix + mstr + ":" + sstr;
  }
};

module.exports = Utils;
