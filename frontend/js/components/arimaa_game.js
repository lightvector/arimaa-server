var React = require('react');
var Board = require('./board.js');
var Movelist = require('./movelist.js');
var GameClock = require('./gameClock.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var DebugComp = require('./boardDebugComponent.js');
var Chat = require('../components/chat.js');
var TopPlayerInfo = require('../components/topPlayerInfo.js');
var BottomPlayerInfo = require('../components/bottomPlayerInfo.js');
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
              <TopPlayerInfo/>
              <Movelist gameID={this.props.params.gameID}/>
              <BottomPlayerInfo/>
              <BoardButtons gameID={this.props.params.gameID}/>
            </div>
          </div>
          <Chat params={{chatChannel:"game/"+this.props.params.gameID}}/>
        </div>
    );
  }
});

module.exports = Game;
