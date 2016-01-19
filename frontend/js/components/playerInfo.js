var React = require('react');

var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

var Utils = require('../utils/Utils.js');

var GameClock = React.createClass({
  getInitialState: function() {
    return {
      clock: ArimaaStore.getClockRemaining(this.props.pos, false),
      playerInfo: ArimaaStore.getUserInfo(this.props.pos),
      player: ArimaaStore.getPlayerOfPos(this.props.pos),
      playerToMove: ArimaaStore.getColorToMove(),
      tc: ArimaaStore.getTCOfPos(this.props.pos),
      gameState: ArimaaStore.getGameState()
    };
  },

  _onChange: function() {
    this.setState({
      clock: ArimaaStore.getClockRemaining(this.props.pos, false),
      playerInfo: ArimaaStore.getUserInfo(this.props.pos),
      player: ArimaaStore.getPlayerOfPos(this.props.pos),
      playerToMove: ArimaaStore.getColorToMove(),
      tc: ArimaaStore.getTCOfPos(this.props.pos),
      gameState: ArimaaStore.getGameState()
    });
  },

  componentDidMount: function() {
    ArimaaStore.addChangeListener(this._onChange);
    this.interval = setInterval(this._onChange,150);
  },

  componentWillUnmount: function() {
    ArimaaStore.removeChangeListener(this._onChange);
    clearInterval(this.interval);
  },

  render: function() {
    //TODO take into account maxMoveTime!!
    var clock = this.state.clock;
    var clockFormatted = "-:--";
    if(clock !== null)
      clockFormatted = Utils.timeSpanToString(Math.max(0,clock));

    var userInfoString = Utils.userDisplayStr(this.state.playerInfo);
    if(this.state.gameState && this.state.gameState.meta.activeGameData) {
      var activeGameData = this.state.gameState.meta.activeGameData;
      var present = this.state.player == ArimaaConstants.GAME.GOLD ? activeGameData.gPresent : activeGameData.sPresent;
      if(!present)
        userInfoString += " (disconnected)";
    }

    var panelColor = (this.state.player == ArimaaConstants.GAME.GOLD ? "goldPlayerPanel" : "silverPlayerPanel");

    var clockClass = "clockSpan";
    if(clock < 10)
      clockClass += " clockVeryLowTime";
    else if(clock < 60)
      clockClass += " clockLowTime";

    var tcFormatted = "";
    var tcClass = "tcSpan";
    if(this.state.tc && this.state.gameState) {

      var overtimeTC = Utils.overtimeTC(this.state.tc, this.state.gameState.meta.numPly);
      if(overtimeTC) {
        tcClass = "tcOvertime";
        tcFormatted = Utils.gameTCString(overtimeTC);
      }
      else {
        tcFormatted = Utils.gameTCString(this.state.tc);
      }
    }

    var toMove = "";
    if(this.state.gameState &&
       this.state.gameState.meta.activeGameData &&
       this.state.playerToMove !== ArimaaConstants.GAME.NULL_COLOR &&
       this.state.playerToMove == this.state.player)
      toMove = " toMove";

    if(this.props.pos === "top") {
      return (
        <div className={"topPlayerInfo " + panelColor + toMove}>
          <span className="playerName"> {userInfoString} </span>
          <br/>
          <span className={clockClass}> {clockFormatted} </span> <span className={tcClass}> {tcFormatted} </span>
        </div>
      );
    } else {
      return (
        <div className={"bottomPlayerInfo " + panelColor + toMove}>
          <span className="playerName"> {userInfoString} </span>
          <br/>
          <span className={clockClass}> {clockFormatted} </span> <span className={tcClass}> {tcFormatted} </span>
        </div>
      );
    }
  }

});

module.exports = GameClock;
