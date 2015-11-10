var React = require('react');
var ReactDOM = require('react-dom');
var Modal = require('react-modal');
// var Modal = require('react-bootstrap/lib/Modal');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var Utils = require('../utils/Utils.js');
var CreateGameDialog = require('../components/createGameDialog.js');
var Chat = require('../components/chat.js');

var component = React.createClass({
  getInitialState: function() {
    return {message: "", error:"",
            ownGames:[], joinableOpenGames:[], watchableGames:[], selectedPlayers:{},
            createGameDialogOpen:false};
  },

  componentDidMount: function() {
    UserStore.addChangeListener(this.onUserStoreChange);
    SiteActions.beginOpenGamesLoop();
    SiteActions.beginActiveGamesLoop();
  },
  componentWillUnmount: function() {
    UserStore.removeChangeListener(this.onUserStoreChange);
  },

  onUserStoreChange: function() {
    this.setState(UserStore.getMessageError());
    this.setState({
      ownGames:UserStore.getOwnGames(),
      joinableOpenGames:UserStore.getJoinableOpenGames(),
      watchableGames:UserStore.getWatchableGames()
    });
  },

  handleJoinedPlayerSelection: function(gameID, evt) {
    console.log(evt);
    var playerName = evt.target.value;
    var selectedPlayers = clone(this.state.selectedPlayers);
    selectedPlayers[gameID] = playerName;
    this.setState({selectedPlayers: selectedPlayers});
  },

  joinGameButtonClicked: function(gameID, evt) {
    // evt.target.disabled = true;
    SiteActions.joinGame(gameID);
  },

  acceptUserButtonClicked: function(gameID, gameAuth, username, evt) {
    // evt.target.disabled = true;
    SiteActions.acceptUserForGame(gameID, gameAuth, username);
  },

  declineUserButtonClicked: function(gameID, gameAuth, username, evt) {
    // evt.target.disabled = true;
    SiteActions.declineUserForGame(gameID, gameAuth, username);
  },

  leaveButtonClicked: function(gameID, gameAuth, evt) {
    // evt.target.disabled = true;
    SiteActions.leaveGame(gameID, gameAuth);
  },

  gameButtonClicked: function(gameID) {
    //window.location.pathname = "/game/" + gameID;
    window.open("/game/" + gameID);
  },

  createButtonClicked: function() {
    this.setState({createGameDialogOpen:true});
  },
  closeCreateDialog: function() {
    this.setState({createGameDialogOpen:false});
  },

  handleCreateGameSubmitted: function(opts) {
    this.setState({createGameDialogOpen:false});
    SiteActions.createGame(opts);
  },

  gameTitle: function(metadata) {
    var title = "";
    var hasCreator = metadata.openGameData !== undefined && metadata.openGameData.creator !== undefined;

    if(metadata.gUser !== undefined && metadata.sUser !== undefined)
      title = metadata.gUser.name + " (G) vs " + metadata.sUser.name + " (S)";
    else if(metadata.gUser !== undefined && hasCreator && metadata.openGameData.creator.name != metadata.gUser.name)
      title = metadata.gUser.name + " (G) vs " + metadata.openGameData.creator.name + " (S)";
    else if(metadata.sUser !== undefined && hasCreator && metadata.openGameData.creator.name != metadata.sUser.name)
      title = metadata.openGameData.creator.name + " (G) vs " + metadata.sUser.name + " (S)";
    else if(metadata.gUser !== undefined)
      title = metadata.gUser.name + " (G) vs " + "anyone" + " (S)";
    else if(metadata.sUser !== undefined)
      title = "anyone (G)" + " vs " + metadata.sUser.name + " (S)";
    else if(metadata.openGameData.creator !== undefined)
      title = metadata.openGameData.creator.name + " vs " + "anyone" + " (random color)";
    else
      title = "anyone" + " vs " + "anyone" + " (random color)";

    if(metadata.tags.length > 0)
      title = title + " (" + metadata.tags.join(", ") + ")";
    return title;
  },

  gameInfoString: function(metadata) {
    var infos = [];

    if(metadata.gameType !== undefined && metadata.gameType.length > 0)
      infos.push(metadata.gameType.charAt(0).toUpperCase() + metadata.gameType.slice(1));

    if(metadata.openGameData !== undefined)
      infos.push("Open");
    if(metadata.activeGameData !== undefined)
      infos.push("Active");

    if(metadata.rated)
      infos.push("Rated");
    else
      infos.push("Unrated");

    if(metadata.postal)
      infos.push("Postal");

    if(metadata.result !== undefined)
      infos.push(metadata.result.winner + "+" + metadata.result.reason);

    var gTC = Utils.gameTCString(metadata.gTC);
    var sTC = Utils.gameTCString(metadata.sTC);
    if(gTC != sTC) {
      infos.push("Gold TC:"+gTC);
      infos.push("Silver TC:"+sTC);
    }
    else
      infos.push("Time control: "+gTC);

    if(metadata.numPly > 0)
      infos.push("Move " + (Math.floor((metadata.numPly+2)/2)) + (metadata.numPly % 2 == 0 ? "g" : "s"));

    return infos.join(", ");
  },

  renderGame: function(metadata) {
    var title = this.gameTitle(metadata);
    var info = this.gameInfoString(metadata);
    var joinAccepts = [];
    var leaveButton = [];
    var gameButton = [];

    var gameAuth = UserStore.getJoinedGameAuth(metadata.gameID);

    var username = UserStore.getUsername();
    if(metadata.openGameData !== undefined) {
      var joined = false;
      var joinedNotUs = [];
      for(var j = 0; j<metadata.openGameData.joined.length; j++) {
        if(metadata.openGameData.joined[j].name == username)
          joined = true;
        else
          joinedNotUs.push(metadata.openGameData.joined[j]);
      }

      if(!joined)
        joinAccepts.push(<button onClick={this.joinGameButtonClicked.bind(this, metadata.gameID)}>Play</button>);
      else if(joinedNotUs.length <= 0 || (metadata.openGameData.creator !== undefined && metadata.openGameData.creator.name != username))
        joinAccepts.push(<span>Waiting for opponent...</span>);
      else {
        var selectedPlayer = null;
        if(metadata.gameID in this.state.selectedPlayers) {
          var name = this.state.selectedPlayers[metadata.gameID];
          for(var i = 0; i<joinedNotUs.length; i++) {
            if(joinedNotUs[i].name == name) {
              selectedPlayer = name;
              break;
            }
          }
        }
        else {
          selectedPlayer = joinedNotUs[0].name;
        }

        var selections = [];
        for(var i = 0; i<joinedNotUs.length; i++) {
          var userinfo = joinedNotUs[i];
          selections.push(React.createElement("option", {key: "joinedPlayer_"+metadata.gameID+"_"+userinfo.name, value: userinfo.name}, userinfo.name));
        }
        var selector;
        if(selectedPlayer !== null)
          selector = React.createElement("select", {key: "joinedPlayerSelect_"+metadata.gameID, size: joinedNotUs.length,
                                                    onchange: this.handleJoinedPlayerSelection.bind(this, metadata.gameID), value: selectedPlayer}, selections);
        else
          selector = React.createElement("select", {key: "joinedPlayerSelect_"+metadata.gameID, size: joinedNotUs.length,
                                                    onchange: this.handleJoinedPlayerSelection.bind(this, metadata.gameID)}, selections);

        joinAccepts.push(<span>Someone has joined your game: </span>);
        joinAccepts.push(selector);
        if(selectedPlayer !== null && gameAuth !== null) {
          joinAccepts.push(React.createElement(
            "button",
            {key: "acceptButton_"+metadata.gameID, onClick: this.acceptUserButtonClicked.bind(this, metadata.gameID, gameAuth, userinfo.name)},
            "Accept and play " + selectedPlayer
          ));
          joinAccepts.push(React.createElement(
            "button",
            {key: "declineButton_"+metadata.gameID, onClick: this.declineUserButtonClicked.bind(this, metadata.gameID, gameAuth, userinfo.name)},
            "Decline " + selectedPlayer
          ));
        }
        else {
          joinAccepts.push(React.createElement(
            "button",
            {key: "acceptButton_"+metadata.gameID, disabled: true, onClick: function() {return false;}},
            "Accept and play"
          ));
          joinAccepts.push(React.createElement(
            "button",
            {key: "declineButton_"+metadata.gameID, disabled: true, onClick: function() {return false;}},
            "Decline"
          ));
        }
      }

      if(joined) {
        if(gameAuth !== null)
          leaveButton.push(React.createElement("button", {key: "leaveButton_"+metadata.gameID, onClick: this.leaveButtonClicked.bind(this,metadata.gameID, gameAuth)}, "Cancel"));
        else
          leaveButton.push(React.createElement("button", {key: "leaveButton_"+metadata.gameID, disabled: true, onClick: function() {return false;}}, "Cancel"));
      }
    }
    if(metadata.activeGameData !== undefined) {
      if(metadata.gUser.name == username || metadata.sUser.name == username)
        gameButton.push(React.createElement("button", {key: "gameButton_"+metadata.gameID, onClick: this.gameButtonClicked.bind(this,metadata.gameID)}, "Rejoin Game"));
      else
        gameButton.push(React.createElement("button", {key: "gameButton_"+metadata.gameID, onClick: this.gameButtonClicked.bind(this,metadata.gameID)}, "Watch Game"));
    }

    var elts = [];

    elts.push(React.createElement("div", {key: "title_"+metadata.gameID}, title));
    elts.push(React.createElement("div", {key: "info_"+metadata.gameID}, info));
    elts.push(React.createElement("div", {key: "joinAccepts_"+metadata.gameID},joinAccepts));
    if(leaveButton.length > 0)
      elts.push(React.createElement("div", {key: "leaveButtonDiv_"+metadata.gameID},leaveButton));
    if(gameButton.length > 0)
      elts.push(React.createElement("div", {key: "gameButtonDiv_"+metadata.gameID},gameButton));

    return React.createElement("div", {key: "main_"+metadata.gameID}, elts);
  },

  render: function() {
    var that = this;
    var username = UserStore.getUsername();

    // var createModal = (
    //     <Modal show={this.state.createGameDialogOpen} onHide={this.closeCreateDialog}>
    //     <CreateGameDialog handleSubmitted={this.handleCreateGameSubmittedonRequestClose}/>
    //     </Modal>
    // );
    var createModal = (
        <Modal isOpen={this.state.createGameDialogOpen} onRequestClose={this.closeCreateDialog}>
        <CreateGameDialog handleSubmitted={this.handleCreateGameSubmitted}/>
        </Modal>
    );

    var errorDiv = "";
    if(this.state.error != "") {
      errorDiv = React.createElement("div", {className:"error"}, this.state.error);
    }

    var ownGamesDiv = "";
    {
      var ownGamesList = this.state.ownGames.map(function(metadata) {
        return that.renderGame(metadata);
      });

      ownGamesList.unshift(React.createElement("button", {key: "createGameButton", onClick: this.createButtonClicked}, "Create New Game"));
      ownGamesList.unshift(React.createElement("h3", {}, "My Current Games"));
      ownGamesDiv = React.createElement("div", {key: "ownDiv"}, ownGamesList);
    }

    joinableOpenGamesDiv = "";
    {
      var joinableOpenGamesList = this.state.joinableOpenGames.map(function(metadata) {
        return that.renderGame(metadata);
      });

      joinableOpenGamesList.unshift(React.createElement("h3", {}, "Open Games"));
      joinableOpenGamesDiv = React.createElement("div", {key: "joinableOpenDiv"}, joinableOpenGamesList);
    }


    watchableGamesDiv = "";
    {
      var watchableGamesList = this.state.watchableGames.map(function(metadata) {
        return that.renderGame(metadata);
      });

      watchableGamesList.unshift(React.createElement("h3", {}, "Active Games"));
      watchableGamesDiv = React.createElement("div", {key: "watchableDiv"}, watchableGamesList);
    }

    var chat = (
        <Chat params={{chatChannel:"main"}}/>
    );


    var contents = [
      React.createElement("h2", {}, "Arimaa Gameroom"),
      createModal,
      errorDiv,
      ownGamesDiv,
      joinableOpenGamesDiv,
      watchableGamesDiv,
      chat
    ];

    //TODO weird classname?
    return React.createElement("div", {className: "commentBox"}, contents);

  }

});

module.exports = component;
