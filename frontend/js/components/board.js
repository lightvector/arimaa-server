var React = require('react');
var Square = require('./square.js');
var Piece = require('./piece.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

function getGameState() {
  var boardState= {
    fen: ArimaaStore.getArimaa().get_fen(),
    stepFrom: ArimaaStore.getSeletedSquare().num, //need to change this name
    steps: ArimaaStore.getValidSteps(),
    viewSide: ArimaaStore.getViewSide()
  };
  return boardState;
}

var Board = React.createClass({
  getInitialState: function() {
    return {fen: ArimaaStore.getArimaa().get_fen(), stepFrom: ArimaaConstants.GAME.NULL_SQUARE_NUM, steps: [], viewSide: ArimaaConstants.GAME.SILVER}; //move constants into store?
  },

  componentDidMount: function() {
     ArimaaStore.addChangeListener(this._onChange);
   },

   componentWillUnmount: function() {
     ArimaaStore.removeChangeListener(this._onChange);
   },

  squareClicked: function(i, sqName) {
    ArimaaActions.clickSquare(i, sqName);
  },

  renderSquare: function(p, i) {
    if(this.state.viewSide === ArimaaConstants.GAME.SILVER) {
      i = 63-i;
    }

    var x = i%8;
    var y = Math.floor(i/8);

    const ranks = "87654321";
    const files = "abcdefgh";
    var squareName =  files.charAt(x)+ranks.charAt(y);

    var black = (x+y) % 2 === 1;
    var trap = (x === 2 || x === 5) && (y === 2 || y === 5);
    var selected = (this.state.stepFrom === i);
    var piece = (p !== ' ') ? (<Piece pieceName={p}/>) : null;

    var stepTo = false;
    for(var iii=0;iii<this.state.steps.length;iii++) {
      var s = this.state.steps[iii];
      if(s.destSquare === squareName) stepTo = true;
    }

    return (
      <div key={i} className="square" onClick={this.squareClicked.bind(this, i, squareName)}>
        <Square black={black} trap={trap} selected={selected} stepTo={stepTo}>
          {piece}
        </Square>
      </div>
    );
  },

  render: function() {
    var position = [];
    for(var i=0;i<this.state.fen.length;i++) {
      var c = this.state.fen.charAt(i);
      if(c == '/') {continue;}
      else if("12345678".indexOf(c) !== -1) {
        for(var j=0;j<parseInt(c);j++) {position.push(' ');}
      } else {position.push(c);}
    }

    if(this.state.viewSide === ArimaaConstants.GAME.SILVER) {
      position.reverse();
    }

    var squares = position.map(this.renderSquare, this);

    return (
      <div className="board">
        {squares}
      </div>
    );
  },

  _onChange: function() {
      this.setState(getGameState());
  }

});

module.exports = Board;
