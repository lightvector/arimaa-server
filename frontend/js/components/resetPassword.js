var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var resetPasswordBox = React.createClass({
  getInitialState: function() {
    return {pass: "", confirmPass: "", message: "", error: ""};
  },

  componentDidMount: function() {
    UserStore.addChangeListener(this.onUserStoreChange);
  },
  componentWillUnmount: function() {
    UserStore.removeChangeListener(this.onUserStoreChange);
  },

  onUserStoreChange: function() {
    this.setState(UserStore.getMessageError());
  },

  handlePasswordChange: function(event) {
    this.setState({pass: event.target.value});
  },
  handleConfirmPasswordChange: function(event) {
    this.setState({confirmPass: event.target.value});
  },
  submitResetPassword: function(event) {
    event.preventDefault();
    if(this.state.confirmPass !== this.state.pass)
      this.setState({error:"'Password' and 'Confirm Password' fields do not match"});
    else
      SiteActions.resetPassword(this.props.username, this.props.resetAuth, this.state.pass);
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

    //TODO here and elsewhere, should we be disabling the buttons upon submit so that we don't accidentally get double-sends of requests?
    return (
      <div>
        <div className="uiPanel center">
          <h1>Reset Password</h1>
          <form method="post" action="index.html">
            <input type="password" name="password" value={this.state.pass} onChange={this.handlePasswordChange} placeholder="New Password"/>
            <input type="password" name="confirmPassword" value={this.state.confirmPass} onChange={this.handleConfirmPasswordChange} placeholder="Confirm New Password"/>
            <input type="submit" className="submit majorButton" name="commit" value="Set New Password" onClick={this.submitResetPassword}/>
          </form>
          {errorText}
          <div className="vPadding"><a href="/">Back to Login</a></div>
        </div>
      </div>
    );
  }
});

module.exports = resetPasswordBox;
