var React = require('react');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaStore = require('../stores/ArimaaStore.js');

var component = React.createClass({
  getInitialState: function() {
    return {debugMsg:""};
  },

  componentDidMount: function() {
     ArimaaStore.addChangeListener(this._onChange);
  },
  componentWillUnmount: function() {
     ArimaaStore.removeChangeListener(this._onChange);
  },
  _onChange: function() {
    //alert(ArimaaStore.getDebugMsg());
    this.setState({debugMsg:ArimaaStore.getDebugMsg()});
  },

  moveFromText: function() {
    console.log('move from text: ' + this.refs.move.getDOMNode().value);
    ArimaaActions.addMove(this.refs.move.getDOMNode().value);
  },

  completeMove: function() {
    ArimaaActions.completeMove();
  },

  undoStep: function() {
    ArimaaActions.undoStep();
  },

  redoStep: function() {
    ArimaaActions.redoStep();
  },

  forfeit: function() {
    alert("NOT YET IMPLEMENTED");
  },

  leave: function() {
    alert("NOT YET IMPLEMENTED");
  },

  render: function() {
    return (
      <div>
        <input type="text" placeholder="enter move.. Ra1n Cb1n ..." ref="move"></input>
        <button onClick={this.moveFromText}>Move from text</button>
        <button onClick={this.completeMove}>Complete Move</button>
        <button onClick={this.undoStep}>Undo Step</button>
        <button onClick={this.redoStep}>Redo Step</button>
        <button onClick={this.forfeit}>Forfeit</button>
        <button onClick={this.leave}>Leave</button><br/>
        <textarea value={this.state.debugMsg} readOnly></textarea>
      </div>
    );
  }
});

module.exports = component;
