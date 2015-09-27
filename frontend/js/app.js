

var React = require('react');
var Arimaa = require('./lib/arimaa.js');


var Game = require('./components/arimaa_game.js');
var Header = require('./components/header.js');
var Login = require('./components/login.js');
var ForgotPassword = require('./components/forgotPassword.js');
var Register = require('./components/register.js');
var DebugComp = require('./components/generalDebugComponent.js');

var url = window.location.pathname;

//console.log(url);

var gameRegex = /\/game\/([0-9a-f]+)/;
var gameMatch = url.match(gameRegex);

//TODO definitely make a better router later lol
if(url == "/login") {
  React.render(<Login/>, document.getElementById('board_container'));
} else if(url == "/register") {
  React.render(<Register/>, document.getElementById('board_container'));
} else if(url == "/forgotPassword") {
  React.render(<ForgotPassword/>, document.getElementById('board_container'));
} else if(gameMatch) {
  console.log("game id: " + gameMatch[1]);
  React.render(<Game gameID={gameMatch[1]}/>, document.getElementById('board_container'));
} else {
  React.render(<DebugComp/>, document.getElementById('board_container'));
}

//React.render(<Header/>, document.getElementById('board_container'));
