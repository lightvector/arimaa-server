var React = require('react');
var ReactDOM = require('react-dom');
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
var Utils = require('./utils/Utils.js');

var Router = require('react-router').Router;
var Route = require('react-router').Route;
var Link = require('react-router').Link;

//Initialize focus handler
Utils.initWindowOnFocus();

const createBrowserHistory = require('history/lib/createBrowserHistory');

const App = React.createClass({
  render() {
    return (
      <div className="app" >
        {this.props.children}
      </div>
    );
  }
});

const routes = {
  path: '/',
  component: App,
  indexRoute: { component:Login },
  childRoutes: [
    { path: 'debug', component: DebugComp }, //TODO remove this for actual release!!!
    { path: 'register', component: Register },
    { path: 'resetPassword/:username/:resetAuth', component: ResetPassword },
    { path: 'forgotPassword', component: ForgotPassword },
    { path: 'gameroom', component: Gameroom },
    { path: 'game/:gameID', component: Game },
    { path: 'chat/:chatChannel', component: Chat }
  ]
};

ReactDOM.render(<Router routes={routes} history={createBrowserHistory()} />, document.getElementById('app_container'));
