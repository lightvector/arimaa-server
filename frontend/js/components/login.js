var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var Link = require('react-router').Link;
var TitleLine = require('../components/titleLine.js');

var loginBox = React.createClass({
  getInitialState: function() {
    return {user: "", pass: "", message: "", error: ""};
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

  handleUsernameChange: function(event) {
    this.setState({user: event.target.value});
  },
  handlePasswordChange: function(event) {
    this.setState({pass: event.target.value});
  },

  submitLogin: function(event) {
    event.preventDefault();
    SiteActions.login(this.state.user, this.state.pass);
  },

  submitLoginAsGuest: function(event) {
    event.preventDefault();
    SiteActions.loginGuest(this.state.user);
  },

  render: function() {
    var errorText = null;
    if(this.state.error != "") {
      errorText = (<div className="error">{this.state.error}</div>);
    }

    return (
      <div className="center">
        <TitleLine/>
        <div className="uiPanel">
          <h1>Login</h1>
          <form method="post" action="index.html">
            <input type="text" name="login" value={this.state.user} onChange={this.handleUsernameChange} placeholder="Username/Email"/>
            <input type="password" name="password" value={this.state.pass} onChange={this.handlePasswordChange} placeholder="Password"/>
            <input type="submit" className="submit majorButton" name="commit" value="Login" onClick={this.submitLogin}/>
            <input type="submit" className="submit majorButton" name="commitGuest" value="Enter As Guest" onClick={this.submitLoginAsGuest}/>
          </form>
          {errorText}
          <div className="vPadding small"><Link to="/forgotPassword">Forgot Password?</Link></div>
          <div className="small"><Link to="/register">Register</Link></div>
        </div>
      </div>
    );
  }
});

module.exports = loginBox;
