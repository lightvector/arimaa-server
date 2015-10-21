var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var loginBox = React.createClass({
  getInitialState: function() {
    return {user: "", pass: "", error: ""};
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

  componentDidMount: function() {
    UserStore.addChangeListener(this._onChange);
  },

  componentWillUnmount: function() {
    UserStore.removeChangeListener(this._onChange);
  },

  _onChange: function() {
    console.log('onchange');
    this.setState(UserStore.getLoginState());
  },

  render: function() {
    //var value = this.state.value;
    //return <input type="text" value={value} onChange={this.handleChange} />;

    var errorText = null;
    //is empty string falsey?
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
          <div className="forgotpass"><a href="/forgotPassword">Forgot Password?</a></div>
          <div><a href="/register">Register</a></div>
        </div>
      </div>
    );
  }
});

module.exports = loginBox;
