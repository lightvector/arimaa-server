var React = require('react');
var ArimaaActions = require('../actions/ArimaaActions.js');
var SiteActions = require('../actions/SiteActions.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

var component = React.createClass({
  getInitialState: function() {
    return {
      myColor: ArimaaStore.getMyColor(),
      gameOver: ArimaaStore.getGameOver(),
      arimaa: ArimaaStore.getArimaa()
    };
  },

  componentDidMount: function() {
     ArimaaStore.addChangeListener(this._onChange);
  },
  componentWillUnmount: function() {
     ArimaaStore.removeChangeListener(this._onChange);
  },
  _onChange: function() {
    this.setState({
      myColor:ArimaaStore.getMyColor(),
      gameOver:ArimaaStore.getGameOver(),
      arimaa:ArimaaStore.getArimaa()
    });
  },

  completeMove: function() {
    if(ArimaaStore.getSetupColor() === ArimaaConstants.GAME.GOLD) {
      ArimaaActions.sendGoldSetup(this.props.gameID);
    } else if(ArimaaStore.getSetupColor() === ArimaaConstants.GAME.SILVER) {
      ArimaaActions.sendSilverSetup(this.props.gameID);
    } else {
      ArimaaActions.completeMove(this.props.gameID);
    }
  },

  undoStep: function() {
    ArimaaActions.undoStep();
  },

  redoStep: function() {
    ArimaaActions.redoStep();
  },

  flipBoard: function() {
    ArimaaActions.flipBoard();
  },

  forfeit: function() {
    ArimaaActions.forfeit(this.props.gameID);
  },

  render: function() {
    var gameOverString = "";
    if(this.state.gameOver) {
      gameOverString = "winner: " + this.state.gameOver.winner + " reason: " + this.state.gameOver.reason;
    }

    return (
      <div>
        <button onClick={this.completeMove}>Send Move</button>
        <button onClick={this.undoStep}>Undo Step</button>
        <button onClick={this.redoStep}>Redo Step</button>
        <button onClick={this.flipBoard}>Flip Board</button>
        <button onClick={this.forfeit}>Forfeit</button>
        <br/>
        <p/>{gameOverString}<br/>
      </div>
    );
  }
});

module.exports = component;
