var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var registrationBox = React.createClass({
  getInitialState: function() {
    return {user: "", pass: "", email: "", confirmPass: "", error: ""};
  },
  handleUsernameChange: function(event) {
    this.setState({user: event.target.value});
  },
  handlePasswordChange: function(event) {
    this.setState({pass: event.target.value});
  },
  handleEmailChange: function(event) {
    this.setState({email: event.target.value});
  },
  handleConfirmPasswordChange: function(event) {
    this.setState({confirmPass: event.target.value});
  },
  submitRegister: function(event) {
    event.preventDefault();
    //TODO eventually, we will have to add a local check for valid username, email, pass match, etc
    SiteActions.register(this.state.user, this.state.email, this.state.pass);
  },
  componentDidMount: function() {
     UserStore.addChangeListener(this._onChange);
   },
   componentWillUnmount: function() {
     UserStore.removeChangeListener(this._onChange);
   },

  _onChange: function() {
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

    //TODO it's weird to use the "forgotpass" class for the div
    return (
      <div>
        <div className="login">
          <h1>Register</h1>
          <form method="post" action="index.html">
            <input type="text" name="login" value={this.state.user} onChange={this.handleUsernameChange} placeholder="Username"/>
            <input type="text" name="email" value={this.state.email} onChange={this.handleEmailChange} placeholder="Email"/>
            <input type="password" name="password" value={this.state.pass} onChange={this.handlePasswordChange} placeholder="Password"/>
            <input type="password" name="confirmPassword" value={this.state.confirmPass} onChange={this.handleConfirmPasswordChange} placeholder="Confirm Password"/>
            <input type="submit" className="submit" name="commit" value="Register" onClick={this.submitRegister}/>
          </form>
          {errorText}
          <div className="forgotpass"><a href="/login">Back to login</a></div>
        </div>
      </div>
    );
  }
});

module.exports = registrationBox;
