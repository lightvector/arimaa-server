var React = require('react');
var Board = require('./board.js');
var Movelist = require('./movelist.js');
var GameClock = require('./gameClock.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var DebugComp = require('./boardDebugComponent.js');
var Chat = require('../components/chat.js');
var PlayerInfo = require('../components/playerInfo.js');
var BoardButtons = require('../components/boardButtons.js');

var Game = React.createClass({

  componentDidMount: function() {
    ArimaaActions.startAllLoops(this.props.params.gameID);
  },

  render: function() {
    return (

        <div>
          <div>
            <Board gameID={this.props.params.gameID}/>

            <div className="sidepane">
              <PlayerInfo pos={"top"}/>
              <Movelist gameID={this.props.params.gameID}/>
              <PlayerInfo pos={"bottom"}/>
              <BoardButtons gameID={this.props.params.gameID}/>
            </div>
          </div>
          <Chat params={{chatChannel:"game/"+this.props.params.gameID}}/>
        </div>
    );
  }
});

module.exports = Game;
