var React = require('react');
var Board = require('./board.js');
var Movelist = require('./movelist.js');
var GameClock = require('./gameClock.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var DebugComp = require('./boardDebugComponent.js');
var Chat = require('../components/chat.js');
var PlayerInfo = require('../components/playerInfo.js');
var BoardButtons = require('../components/boardButtons.js');
var Utils = require('../utils/Utils.js');

var Game = React.createClass({

  getInitialState: function() {
    return {
      gameState: ArimaaStore.getGameState()
    };
  },

  componentDidMount: function() {
    ArimaaActions.startAllLoops(this.props.params.gameID);
    ArimaaStore.addChangeListener(this._onChange);
  },
  componentWillUnmount: function() {
    ArimaaStore.removeChangeListener(this._onChange);
  },
  
  _onChange: function() {
    this.setState({
      gameState: ArimaaStore.getGameState()
    });
  },

  render: function() {
    var titleString = this.state.gameState ? Utils.gameTitle(this.state.gameState.meta) : "Unknown game";
    if(this.state.gameState && this.state.gameState.meta.result) {
      var result = this.state.gameState.meta.result;
      var reason = "";
      if(result.reason == "g") reason = "Goal";
      else if(result.reason == "e") reason = "Elimination";
      else if(result.reason == "m") reason = "Immobilization";
      else if(result.reason == "t") reason = "Time";
      else if(result.reason == "r") reason = "Resignation";
      else if(result.reason == "f") reason = "Forfeit";
      else if(result.reason == "i") reason = "Illegal Move";
      else if(result.reason == "a") reason = "Adjourned";
      else if(result.reason == "x") reason = "Interrupted";
      else reason = "Result Code " + result.reason;

      if(result.winner == "g") titleString += " - Gold wins by " + reason + " on " + Utils.turnStr(this.state.gameState.meta.numPly);
      else if(result.winner == "s") titleString += " - Silver wins by " + reason + " on " + Utils.turnStr(this.state.gameState.meta.numPly);
      else titleString += " - Game was " + reason;
    }
    else if(this.state.gameState) {
      titleString += " - Turn " + Utils.turnStr(this.state.gameState.meta.numPly);
    }
    return (
        <div>
          <div className="boardTitle">
            <h3> {titleString} </h3>
          </div>
          <div>
            <div className="boardPane">
              <Board gameID={this.props.params.gameID}/>
              <BoardButtons gameID={this.props.params.gameID}/>
            </div>
            <div className="sidePane">
              <PlayerInfo pos={"top"}/>
              <Movelist gameID={this.props.params.gameID}/>
              <PlayerInfo pos={"bottom"}/>
            </div>
          </div>
          <Chat params={{chatChannel:"game/"+this.props.params.gameID}}/>
        </div>
    );
  }
});

module.exports = Game;
