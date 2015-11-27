var React = require('react');

var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

function getGameState() {
  return {moves: ArimaaStore.getMoveList()};
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
      var plyNum = Math.floor(i/2)+1;
      var color = (i%2===0) ? "g" : "s";
      var moveStr = m.map(function(s) {return s.string;}).join(" ");
      //console.log(m);
      //var moveStr = m;
      return (<tr className={color} key={i}><td>{plyNum+color+":"}</td><td>{moveStr}</td></tr>);
    });

    return (
      <div style={{height:'480px'}}>
        <table className="moveList">
        {cells}
        </table>
      </div>
    );
  },

  _onChange: function() {
      this.setState(getGameState());
  }
});

module.exports = Movelist;
