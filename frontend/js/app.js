var React = require('react');
var Arimaa = require('./lib/arimaa.js');


var Game = require('./components/arimaa_game.js');
var Header = require('./components/header.js');
var Login = require('./components/login.js');
var Register = require('./components/register.js');
var ForgotPassword = require('./components/forgotPassword.js');
var ResetPassword = require('./components/resetPassword.js');
var Gameroom = require('./components/gameroom.js');
var Chat = require('./components/chat.js');
var DebugComp = require('./components/generalDebugComponent.js');

var Router = require('react-router').Router
var Route = require('react-router').Route
var Link = require('react-router').Link

const App = React.createClass({
  render() {
    return (
      <div>
        <h1>App</h1>
        <ul>
          <li><Link to="/login">Login</Link></li>
          <li><Link to="/register">Register</Link></li>
        </ul>
        {this.props.children}
      </div>
    )
  }
})

const routes = {
  path: '/',
  component: DebugComp, //replace this for actual release!!!
  childRoutes: [
    { path: 'login', component: Login },
    { path: 'register', component: Register },
    { path: 'resetPassword'}, //this one might need some work
    { path: 'forgotPassword', component: ForgotPassword },
    { path: 'gameroom', component: Gameroom },
    { path: 'game/:gameID', component: Game },
    { path: 'chat/:chatChannel', component: Chat}
  ]
}

React.render(<Router routes={routes} />, document.getElementById('board_container'));
