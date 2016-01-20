var React = require('react');
var ArimaaActions = require('../actions/ArimaaActions.js');
var SiteActions = require('../actions/SiteActions.js');
var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

var component = React.createClass({
  getInitialState: function() {
    return {
      myColor: ArimaaStore.getMyColor(),
      isSendingMoveNow: ArimaaStore.isSendingMoveNow(),
      isOurTurn: ArimaaStore.isOurTurn(),
      isSpectator: ArimaaStore.isSpectator(),
      canUndo: ArimaaStore.canUndo(),
      canRedo: ArimaaStore.canRedo()
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
      isSendingMoveNow: ArimaaStore.isSendingMoveNow(),
      isOurTurn: ArimaaStore.isOurTurn(),
      isSpectator: ArimaaStore.isSpectator(),
      canUndo: ArimaaStore.canUndo(),
      canRedo: ArimaaStore.canRedo()
    });
  },

  completeMove: function() {
    if(ArimaaStore.getSetupColor() !== ArimaaConstants.GAME.NULL_COLOR) {
      ArimaaActions.sendSetup(this.props.gameID);
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

  resign: function() {
    ArimaaActions.resign(this.props.gameID);
  },

  render: function() {
    var s = this.state;
    if(s.isSpectator)
      return (
        <div className="boardButtons">
          <button onClick={this.flipBoard}>Flip Board</button>
        </div>
      );
    else
      return (
        <div className="boardButtons">
          <button onClick={this.completeMove} disabled={s.sendingMoveNow || !s.isOurTurn}>Send Move</button>
          <button onClick={this.undoStep} disabled={s.sendingMoveNow || !s.isOurTurn || !s.canUndo}>Undo Step</button>
          <button onClick={this.redoStep} disabled={s.sendingMoveNow || !s.isOurTurn || !s.canRedo}>Redo Step</button>
          <button onClick={this.flipBoard}>Flip Board</button>
          <button onClick={this.resign}>Resign</button>
        </div>
      );
  }
});

module.exports = component;
