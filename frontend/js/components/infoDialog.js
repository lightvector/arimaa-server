var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var Utils = require('../utils/Utils.js');

var InfoDialog = React.createClass({
  getInitialState: function() {
    return {};
  },

  handleOkButton: function(){
    this.props.handleOk();
  },

  render: function() {
    return(
      <div className={"infoMessage"}>
        <div> {this.props.message} </div>
        <div> <button onClick={this.handleOkButton}>Ok</button> </div>
      </div>
    );
  }
});

module.exports = InfoDialog;
