var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var component = React.createClass({
  getInitialState: function() {
    return {error:'', createdGames:[], openGames:[]};
  },
  
  componentDidMount: function() {
    UserStore.addChangeListener(this._onChange);
    UserStore.addGameMetaChangeListener(this._onGameMetaChange);
    SiteActions.beginOpenGamesLoop();
  },
  componentWillUnmount: function() {
    UserStore.removeChangeListener(this._onChange);
    UserStore.removeChangeListener(this._onGameMetaChange);
  },

  _onGameMetaChange: function() {
    this.setState({
      openGames:UserStore.getOpenGames(),
      createdGames:UserStore.getCreatedGames()
    });
  },

  _onChange: function() {

  },

  render: function() {

    var openGamesList = this.state.openGames.map(function(metadata) {
      return (
        <li key={metadata.id}>
          {metadata.id}
          <button onClick={this.joinGameButtonClicked.bind(this, metadata.id)}>Join</button>
          <button onClick={this.goToGameButtonClicked.bind(this, metadata.id)}>Go To Game</button>
        </li>
      );
    }, this);

    var createdGamesList = this.state.createdGames.map(function(metadata) {
      //slice since object 0 is creator
      var joinedUsers = metadata.openGameData.joined.slice(1).map(function(shortUserData, index) {
        return (
          <li key={index}>
            {shortUserData.name}
            <button onClick={this.acceptUserButtonClicked.bind(this, metadata.id, shortUserData.name)}>Accept</button>
            <button onClick={this.goToGameButtonClicked.bind(this, metadata.id)}>Go To Game</button>
          </li>
        );
      },this);

      return (
        <li key={metadata.id}>
          {metadata.id}
          <ul>
            {joinedUsers}
          </ul>
        </li>

      );
    }, this);

    return (
      <div>
        My Created Games
        <ul>
          {createdGamesList}
        </ul>
        Open Games
        <ul>
          {openGamesList}
        </ul>
      </div>
    );
  }


});

module.exports = component;
