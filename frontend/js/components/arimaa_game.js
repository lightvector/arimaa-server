var React = require('react');
var Board = require('./board.js');
var Movelist = require('./movelist.js');
var GameClock = require('./gameClock.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var DebugComp = require('./boardDebugComponent.js');

var Game = React.createClass({

  componentDidMount: function() {
    ArimaaActions.startAllLoops(this.props.params.gameID);
  },

  render: function() {
    return (
      <div>
        <Board gameID={this.props.params.gameID}/>
        <Movelist gameID={this.props.params.gameID}/>
        <GameClock player="g"/>
        <GameClock player="s"/>
        <DebugComp gameID={this.props.params.gameID}/>
      </div>
    );
  }
});

module.exports = Game;
