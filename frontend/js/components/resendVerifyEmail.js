var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var resendVerifyEmailBox = React.createClass({
  getInitialState: function() {
    return {message: "", error: ""};
  },

  componentDidMount: function() {
    UserStore.addChangeListener(this.onUserStoreChange);
    SiteActions.resendVerifyEmail(UserStore.getUsername());
  },
  componentWillUnmount: function() {
    UserStore.removeChangeListener(this.onUserStoreChange);
  },

  onUserStoreChange: function() {
    this.setState(UserStore.getMessageError());
  },

  render: function() {
    var errorText = null;
    if(this.state.error != "") {
      errorText = (<div className="error">{this.state.error}</div>);
    }
    var messageText = null;
    if(this.state.message != "") {
      messageText = (<div className="vPadding">{this.state.message}</div>);
    }

    return (
      <div>
        <div className="uiPanel center">
          <h1>Resent Account Registration Email</h1>
          {errorText}
          {messageText}
          <div className="vPadding"><a href="/gameroom">Back to Gameroom</a></div>
        </div>
      </div>
    );
  }
});

module.exports = resendVerifyEmailBox;
