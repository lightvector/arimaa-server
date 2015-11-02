

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

var url = window.location.pathname;

//TODO remove this and other debug lines and generally clean up the js code?
//console.log(url);

var resetRegex = /\/resetPassword\/([0-9a-zA-Z\-_]+)\/([0-9a-f]+)/;
var resetMatch = url.match(resetRegex);
var gameRegex = /\/game\/([0-9a-f]+)/;
var gameMatch = url.match(gameRegex);
var chatRegex = /\/chat\/([0-9a-zA-Z_]+)/;
var chatMatch = url.match(chatRegex);

//TODO rename board_container to something more general if it has all of this other stuff?
//TODO definitely make a better router later lol
if(url == "/login") {
  ReactDOM.render(<Login/>, document.getElementById('board_container'));
} else if(url == "/register") {
  ReactDOM.render(<Register/>, document.getElementById('board_container'));
} else if(url == "/forgotPassword") {
  ReactDOM.render(<ForgotPassword/>, document.getElementById('board_container'));
} else if(resetMatch) {
  ReactDOM.render(<ResetPassword username={resetMatch[1]} resetAuth={resetMatch[2]}/>, document.getElementById('board_container'));
} else if(url == "/gameroom") {
  ReactDOM.render(<Gameroom/>, document.getElementById('board_container'));
} else if(gameMatch) {
  console.log("game id: " + gameMatch[1]);
  ReactDOM.render(<Game gameID={gameMatch[1]}/>, document.getElementById('board_container'));
} else if(chatMatch) {
  ReactDOM.render(<Chat chatChannel={chatMatch[1]}/>, document.getElementById('board_container'));
} else {
  //TODO disable this when debugging over to avoid confusing behavior of mistyped urls and such
  ReactDOM.render(<DebugComp/>, document.getElementById('board_container'));
}

//ReactDOM.render(<Header/>, document.getElementById('board_container'));
