
var $ = require('jquery');

//Some miscellaneous useful functions
var inMiddleOfFlash = false;

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
    if(tc.overtimeAfter !== undefined) s += "(ovt " + tc.overtimeAfter + "t)";
    return s;
  },

  //If not overtime, returns null
  //If overtime, adjusts the TC to reflect the overtime factor
  overtimeTC: function(tc, turn) {
    var overtimeFactor = 1.0;
    var overtimeFactorPerTurn = 1.0 - 1.0 / 30.0;
    if(tc.overtimeAfter !== undefined && turn >= tc.overtimeAfter)
      overtimeFactor = Math.pow(overtimeFactorPerTurn, turn - tc.overtimeAfter + 1);
    else
      return null;
    
    var newTC = $.extend(true, {}, tc);    
    if(newTC.increment !== undefined) 
      newTC.increment *= overtimeFactor;
    if(newTC.delay !== undefined) 
      newTC.delay *= overtimeFactor;
    return newTC;
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

  //Recompute the current clock of the specified player as of the snapshot of the gameState from
  //scratch based on all the times recorded in the gameState
  clockRecomputeDirectly: function(player,gameState) {
    var tc = player == "g" ? gameState.meta.gTC : gameState.meta.sTC;
    var clock = tc.initialTime;
    var i = (player == "g" ? 0 : 1);
    for(; i < gameState.moveTimes.length; i += 2) {
      var timeSpent = gameState.moveTimes[i].time - gameState.moveTimes[i].start;
      clock = Utils.clockAfterTurn(clock,timeSpent,i/2,tc);
    }
    if(gameState.toMove == player) {
      var timeSpent = 0;
      if(gameState.meta.activeGameData !== undefined)
        timeSpent = gameState.meta.activeGameData.timeSpent;
      else if(gameState.meta.result !== undefined)
        timeSpent = gameState.meta.result.endTime - gameState.meta.result.lastMoveStartTime;
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
  },

  timeToHHMMSS: function(time) {
    var date = new Date(time*1000);
    var hours = date.getHours();
    var minutes = "0" + date.getMinutes();
    var seconds = "0" + date.getSeconds();
    var formattedTime = hours + ':' + minutes.substr(-2) + ':' + seconds.substr(-2);
    return formattedTime;
  },

  //Given a game metadata, check if the specified user has joined that game
  isUserJoined: function(metadata, username) {
    if(metadata.openGameData !== undefined) {
      for(var j = 0; j<metadata.openGameData.joined.length; j++) {
        if(metadata.openGameData.joined[j].name == username) {
          return true;
        }
      }
    }
    if(metadata.activeGameData !== undefined) {
      if(metadata.gUser.name == username && metadata.activeGameData.gPresent)
        return true;
      if(metadata.sUser.name == username && metadata.activeGameData.sPresent)
        return true;
    }
    return false;
  },

  gameTitle: function(metadata) {
    var title = "";
    var hasCreator = metadata.openGameData !== undefined && metadata.openGameData.creator !== undefined;

    if(metadata.gUser !== undefined && metadata.sUser !== undefined)
      title = Utils.userDisplayStr(metadata.gUser) + " (G) vs " + Utils.userDisplayStr(metadata.sUser) + " (S)";
    else if(metadata.gUser !== undefined && hasCreator && metadata.openGameData.creator.name != metadata.gUser.name)
      title = Utils.userDisplayStr(metadata.gUser) + " (G) vs " + Utils.userDisplayStr(metadata.openGameData.creator) + " (S)";
    else if(metadata.sUser !== undefined && hasCreator && metadata.openGameData.creator.name != metadata.sUser.name)
      title = Utils.userDisplayStr(metadata.openGameData.creator) + " (G) vs " + Utils.userDisplayStr(metadata.sUser) + " (S)";
    else if(metadata.gUser !== undefined)
      title = Utils.userDisplayStr(metadata.gUser) + " (G) vs " + "_" + " (S)";
    else if(metadata.sUser !== undefined)
      title = "_ (G)" + " vs " + Utils.userDisplayStr(metadata.sUser) + " (S)";
    else if(metadata.openGameData.creator !== undefined)
      title = Utils.userDisplayStr(metadata.openGameData.creator) + " vs " + "_" + " (random color)";
    else
      title = "_" + " vs " + "_" + " (random color)";

    if(metadata.tags.length > 0)
      title = title + " (" + metadata.tags.join(", ") + ")";
    return title;
  },

  
  userDisplayStr: function(userInfo) {
    if(userInfo === null) return "";

    var displayStr = userInfo.name;
    var ratingStr = "" + Math.round(userInfo.rating);

    //TODO think about this threshold and/or other ways of displaying the rating
    if(userInfo.ratingStdev > 140)
      ratingStr += "?";
    if(userInfo.ratingStdev > 280)
      ratingStr += "?";

    if(userInfo.isGuest)
      displayStr += " [guest]";
    else if(userInfo.isBot)
      displayStr += " [" + ratingStr + ", bot]";
    else
      displayStr += " [" + ratingStr + "]";
    return displayStr;
  },

  turnStr: function(plyNum) {
    var turn = Math.floor(plyNum/2)+1;
    var color = (plyNum%2===0) ? "g" : "s";
    return turn + color;
  },
  
  flashWindowIfNotFocused: function(reasonTitle) {
    var title = document.title;
    if(!document.hasFocus() && !inMiddleOfFlash) {
      inMiddleOfFlash = true;
      document.title = reasonTitle + " - playarimaa.org";
      setTimeout(function() {
        document.title = title;
        inMiddleOfFlash = false;
        setTimeout(function() {
          Utils.flashWindowIfNotFocused(reasonTitle);
        }, 3000);
      }, 3000);
    }
  },

  //Initialize an onFocusHandler for the window
  onFocusTriggers: [],
  initWindowOnFocus: function() {
    var oldFunc = window.onfocus;
    window.onfocus = function () {
      var arr = Utils.onFocusTriggers;
      Utils.onFocusTriggers = [];
      for(var i = 0; i<arr.length; i++) {
        arr[i]();
      }
      if(oldFunc)
        oldFunc();
    };
  },

  scheduleOnNextFocus: function(f) {
    if(document.hasFocus())
      setTimeout(f,0);
    else
      Utils.onFocusTriggers.push(f);
  },

  setSetting: function(key,value) {
    localStorage.setItem(key,value);
  },
  getSetting: function(key,defaultValue) {
    var result = localStorage.getItem(key);
    if(result === undefined || result === null)
      return defaultValue;
    return result;
  }

};

module.exports = Utils;
