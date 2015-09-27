var React = require('react');
var Board = require('./board.js');
var Movelist = require('./movelist.js');
var DebugComp = require('./boardDebugComponent');

var Game = React.createClass({

  heartbeat: function() {

  },

  componentDidMount: function() {
    this.interval = setInterval(this.heartbeat, 10000);
  },

  componentWillUnmount: function() {
    clearInterval(this.interval);
  },


  render: function() {
    return (
      <div>
        <Board gameID={this.props.gameID}/>
        <Movelist gameID={this.props.gameID}/>
        <DebugComp gameID={this.props.gameID}/>
      </div>
    );
  }
});

module.exports = Game;
