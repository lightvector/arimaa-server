var React = require('react');

var Utils = require('../utils/Utils.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

function getGameState() {
  return {
    moves: ArimaaStore.getMoveList(),
    ongoingMove: ArimaaStore.getOngoingMove()
  };
}

var Movelist = React.createClass({
  getInitialState: function() {
    return getGameState();
  },

  componentDidMount: function() {
     ArimaaStore.addChangeListener(this._onChange);
   },

   componentWillUnmount: function() {
     ArimaaStore.removeChangeListener(this._onChange);
   },

  render: function() {
    var cells = this.state.moves.map(function(m, i) {
      var color = (i%2===0) ? "g" : "s";
      var moveStr = m.map(function(s) {return s.string;}).join(" ");
      return (<tr className={color} key={i}><td>{Utils.turnStr(i)+":"}</td><td>{moveStr}</td></tr>);
    });
    var numMoves = this.state.moves.length;
    var currColor = (numMoves % 2 === 0) ? "g" : "s";

    return (
      <table className="moveList">
        <tbody>
        {cells}
        <tr className={currColor} key="99"><td>{(Math.floor(numMoves/2)+1)+currColor+":"}</td><td>{this.state.ongoingMove}</td></tr>
        </tbody>
      </table>
    );
  },

  _onChange: function() {
      this.setState(getGameState());
  }
});

module.exports = Movelist;
