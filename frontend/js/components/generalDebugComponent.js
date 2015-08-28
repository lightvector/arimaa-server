var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var component = React.createClass({
  componentDidMount: function() {
     UserStore.addChangeListener(this._onChange);
     UserStore.addGameMetaChangeListener(this._onGameMetaChange);
  },
  componentWillUnmount: function() {
     UserStore.removeChangeListener(this._onChange);
     UserStore.removeChangeListener(this._onGameMetaChange);
  },

  _onGameMetaChange: function() {
      console.log('bc');
  },

  _onChange: function() {

  },

  register: function() {
    var user = this.refs.registerUser.getDOMNode().value;
    var pass = this.refs.registerPass.getDOMNode().value;
    var email = this.refs.registerEmail.getDOMNode().value;

    SiteActions.register(user, email, pass);
  },

  createGame: function() {
    var sUser = this.refs.silverUser.getDOMNode().value;
    var gUser = this.refs.goldUser.getDOMNode().value;
    var gameType = this.refs.gameType.getDOMNode().value;
    SiteActions.createGame(gameType);
  },

  joinGame: function() {
    var gID = this.refs.joinGameID.getDOMNode().value;
    SiteActions.joinGame(gID);
  },

  accept: function() {
    var gID = this.refs.acceptGameID.getDOMNode().value;
    var username = this.refs.acceptUsername.getDOMNode().value;
    SiteActions.acceptUserForGame(gID, username);
  },

  gameStatus: function() {
    var gID = this.refs.statusGameID.getDOMNode().value;
    var minSeq = this.refs.statusMinSeq.getDOMNode().value;
    SiteActions.gameStatus(gID, minSeq);
  },

  logout: function() {
    SiteActions.logout();
  },

  render: function() {
    return (
      <div>
        <h1>DEBUG</h1>
        <input type="text" ref="registerUser" placeholder="username"/>
        <input type="text" ref="registerEmail" placeholder="email"/>
        <input type="text" ref="registerPass" placeholder="password"/>
        <button type="button" onClick={this.register}>Register</button>

        <p />
        <input type="text" ref="silverUser" placeholder="goldUser"/>
        <input type="text" ref="goldUser" placeholder="silverUser"/>
        <input type="text" ref="gameType" placeholder="standard,handicap,directsetup" defaultValue="standard"/>
        <button type="button" onClick={this.createGame}>Create Game</button>

        <p />
        <input type="text" ref="joinGameID" placeholder="gameID"/>
        <button type="button" onClick={this.joinGame}>Join Game</button>

        <p />
        <input type="text" ref="statusGameID" placeholder="gameID"/>
        <input type="text" ref="statusMinSeq" placeholder="sequence" defaultValue="0"/>
        <button type="button" onClick={this.gameStatus}>Status</button>

        <p />
        <input type="text" ref="acceptGameID" placeholder="gameID"/>
        <input type="text" ref="acceptUsername" placeholder="username to accept"/>
        <button type="button" onClick={this.accept}>Accept</button>

        <p />
        <button type="button" onClick={this.logout}>Logout</button>
      </div>
    );
  }


});

module.exports = component;
