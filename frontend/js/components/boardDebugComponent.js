var React = require('react');
var ArimaaActions = require('../actions/ArimaaActions.js');
var SiteActions = require('../actions/SiteActions.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

var component = React.createClass({
  getInitialState: function() {
    return {
      debugMsg: "",
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
    //alert(ArimaaStore.getDebugMsg());
    this.setState({
      debugMsg:ArimaaStore.getDebugMsg(),
      myColor:ArimaaStore.getMyColor(),
      gameOver:ArimaaStore.getGameOver(),
      arimaa:ArimaaStore.getArimaa()
    });
  },

  moveFromText: function() {
    console.log('move from text: ' + this.refs.move.getDOMNode().value);
    ArimaaActions.addMove(this.refs.move.getDOMNode().value);
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

  //TODO: change these debugs so only the relevant one for our color shows
  //or condense them into 1 function
  goldSetup: function() {
    //ArimaaActions.completeMove(this.props.gameID, this.refs.goldSetup.getDOMNode().value, 0);
    ArimaaActions.debugSendGoldSetup(this.props.gameID, this.refs.goldSetup.getDOMNode().value);
  },

  silverSetup: function() {
    //ArimaaActions.completeMove(this.props.gameID, this.refs.silverSetup.getDOMNode().value, 1);
    ArimaaActions.debugSendGoldSetup(this.props.gameID, this.refs.silverSetup.getDOMNode().value);
  },

  undoStep: function() {
    ArimaaActions.undoStep();
  },

  redoStep: function() {
    ArimaaActions.redoStep();
  },

  gameState: function() {
    ArimaaActions.gameState(this.props.gameID, this.refs.statusMinSeq.getDOMNode().value);
  },

  flipBoard: function() {
    console.log("flippy");
    ArimaaActions.flipBoard();
  },

  forfeit: function() {
    alert("NOT YET IMPLEMENTED");
  },

  leave: function() {
    alert("NOT YET IMPLEMENTED");
  },

  render: function() {
    var color = "NONE";
    if(this.state.myColor === ArimaaConstants.GAME.GOLD) {
      color = "GOLD";
    } else if(this.state.myColor === ArimaaConstants.GAME.SILVER) {
      color = "SILVER";
    }

    var gameOverString = "In progress...";
    if(this.state.gameOver) {
      gameOverString = "winner: " + this.state.gameOver.winner + " reason: " + this.state.gameOver.reason;
    }

    return (
      <div>
        <input type="text" placeholder="enter move.. Ra1n Cb1n ..." ref="move"></input>
        <button onClick={this.moveFromText}>Move from text</button>
        <br/>

        <input type="text" defaultValue="0" ref="statusMinSeq"></input>
        <button onClick={this.gameState}>Game State</button>
        <button onClick={this.completeMove}>Complete Move</button>
        <button onClick={this.undoStep}>Undo Step</button>
        <button onClick={this.redoStep}>Redo Step</button>
        <button onClick={this.flipBoard}>Flip Board</button>
        <button onClick={this.forfeit}>Forfeit</button>
        <button onClick={this.leave}>Leave</button><br/>
        <p/>{color}
        <p/>{"game status: " + gameOverString}
        <p/>{"gameID: " + this.props.gameID}
        <p/>{"debug msg: " + this.state.debugMsg}<br/>

      </div>
    );
  }
});

module.exports = component;
