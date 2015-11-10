var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var Link = require('react-router').Link;

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

  render: function() {
    var errorText = null;
    if(this.state.error != "") {
      errorText = (<div className="error">{this.state.error}</div>);
    }

    return (
      <div>
        <div className="login">
          <h1>Login</h1>
          <form method="post" action="index.html">
            <input type="text" name="login" value={this.state.user} onChange={this.handleUsernameChange} placeholder="Username/Email"/>
            <input type="password" name="password" value={this.state.pass} onChange={this.handlePasswordChange} placeholder="Password"/>
            <input type="submit" className="submit" name="commit" value="Login" onClick={this.submitLogin}/>
          </form>
          {errorText}
          <div className="forgotpass"><Link to="/forgotPassword">Forgot Password?</Link></div>
          <div><Link to="/register">Register</Link></div>
        </div>
      </div>
    );
  }
});

module.exports = loginBox;
