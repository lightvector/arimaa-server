var React = require('react');
var ReactDOM = require('react-dom');
var Modal = require('react-modal');
var ClassNames = require('classnames');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var Utils = require('../utils/Utils.js');
var CreateGameDialog = require('../components/createGameDialog.js');
var InfoDialog = require('../components/infoDialog.js');
var Chat = require('../components/chat.js');

var component = React.createClass({
  getInitialState: function() {
    return {message: "", error:"",
            ownGames:[], joinableOpenGames:[], watchableGames:[], selectedPlayers:{},
            usersLoggedIn:[],
            notifications:[],
            recentHighlightGameIDs:{},
            recentPlayingGameIDs:{},
            createGameDialogOpen:false,
            popupMessage:"",
            popupMessageOpen:false};
  },

  componentDidMount: function() {
    UserStore.addChangeListener(this.onUserStoreChange);
    UserStore.addPopupMessageListener(this.onPopupMessage);
    SiteActions.beginLoginCheckLoop();
    SiteActions.beginUsersLoggedInLoop();
    SiteActions.beginNotificationsLoop();
    SiteActions.beginOpenGamesLoop();
    SiteActions.beginActiveGamesLoop();
  },
  componentWillUnmount: function() {
    UserStore.removeChangeListener(this.onUserStoreChange);
    UserStore.removePopupMessageListener(this.onPopupMessage);
  },

  onUserStoreChange: function() {
    this.setState(UserStore.getMessageError());
    this.setState({
      ownGames:UserStore.getOwnGames(),
      joinableOpenGames:UserStore.getJoinableOpenGames(),
      watchableGames:UserStore.getWatchableGames(),
      recentHighlightGameIDs:UserStore.getRecentHighlightGames(),
      recentPlayingGameIDs:UserStore.getRecentPlayingGames(),
      usersLoggedIn:UserStore.getUsersLoggedIn(),
      notifications:UserStore.getNotifications()
    });
  },

  onPopupMessage: function(message) {
    this.setState({
      popupMessage:message,
      popupMessageOpen:true
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
  closePopupMessage: function() {
    this.setState({popupMessageOpen:false});
  },

  handleCreateGameSubmitted: function(opts) {
    this.setState({createGameDialogOpen:false});
    SiteActions.createGame(opts);
  },
  handleCreateGameCancelled: function(opts) {
    this.setState({createGameDialogOpen:false});
  },

  handlePopupOk: function(opts) {
    this.setState({popupMessageOpen:false});
  },

  gameTitle: function(metadata) {
    var title = "";
    var hasCreator = metadata.openGameData !== undefined && metadata.openGameData.creator !== undefined;

    if(metadata.gUser !== undefined && metadata.sUser !== undefined)
      title = Utils.userDisplayStr(metadata.gUser) + " (G) vs " + Utils.userDisplayStr(metadata.sUser) + " (S)";
    else if(metadata.gUser !== undefined && hasCreator && metadata.openGameData.creator.name != metadata.gUser.name)
      title = Utils.userDisplayStr(metadata.gUser) + " (G) vs " + Utils.userDisplayStr(metadata.openGameData.creator) + " (S)";
    else if(metadata.sUser !== undefined && hasCreator && metadata.openGameData.creator.name != metadata.sUser.name)
      title = Utils.userDisplayStr(metadata.openGameData.creator) + " (G) vs " + Utils.userDisplayStr(metadata.sUser) + " (S)";
    else if(metadata.gUser !== undefined)
      title = Utils.userDisplayStr(metadata.gUser) + " (G) vs " + "_" + " (S)";
    else if(metadata.sUser !== undefined)
      title = "_ (G)" + " vs " + Utils.userDisplayStr(metadata.sUser) + " (S)";
    else if(metadata.openGameData.creator !== undefined)
      title = Utils.userDisplayStr(metadata.openGameData.creator) + " vs " + "_" + " (random color)";
    else
      title = "_" + " vs " + "_" + " (random color)";

    if(metadata.tags.length > 0)
      title = title + " (" + metadata.tags.join(", ") + ")";
    return title;
  },

  gameInfoString: function(metadata) {
    var infos = [];

    if(metadata.gameType !== undefined && metadata.gameType.length > 0 && metadata.gameType !== "standard")
      infos.push(metadata.gameType.charAt(0).toUpperCase() + metadata.gameType.slice(1));

    // if(metadata.openGameData !== undefined)
    //   infos.push("Open");
    // if(metadata.activeGameData !== undefined)
    //   infos.push("Active");

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
        joinAccepts.push(<button className="gameButton" onClick={this.joinGameButtonClicked.bind(this, metadata.gameID)}>Play</button>);
      else if(metadata.openGameData.creator !== undefined && metadata.openGameData.creator.name != username) {
        joinAccepts.push(<span>Requested game, waiting for opponent to reply...</span>);
        if(gameAuth !== null)
          joinAccepts.push(React.createElement("button", {key: "leaveButton_"+metadata.gameID, className: "no", onClick: this.leaveButtonClicked.bind(this,metadata.gameID, gameAuth)}, "Cancel"));
        else
          joinAccepts.push(React.createElement("button", {key: "leaveButton_"+metadata.gameID, className: "no", disabled: true, onClick: function() {return false;}}, "Cancel"));
      }
      else if(joinedNotUs.length <= 0) {
        joinAccepts.push(<span>Waiting for opponent to join...</span>);
        if(gameAuth !== null)
          joinAccepts.push(React.createElement("button", {key: "leaveButton_"+metadata.gameID, className: "no", onClick: this.leaveButtonClicked.bind(this,metadata.gameID, gameAuth)}, "Cancel"));
        else
          joinAccepts.push(React.createElement("button", {key: "leaveButton_"+metadata.gameID, className: "no", disabled: true, onClick: function() {return false;}}, "Cancel"));
      }
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
          selections.push(React.createElement("option", {key: "joinedPlayer_"+metadata.gameID+"_"+userinfo.name, value: userinfo.name}, Utils.userDisplayStr(userinfo)));
        }
        var selector;
        if(selectedPlayer !== null)
          selector = React.createElement("select", {key: "joinedPlayerSelect_"+metadata.gameID, size: joinedNotUs.length,
                                                    onchange: this.handleJoinedPlayerSelection.bind(this, metadata.gameID), value: selectedPlayer}, selections);
        else
          selector = React.createElement("select", {key: "joinedPlayerSelect_"+metadata.gameID, size: joinedNotUs.length,
                                                    onchange: this.handleJoinedPlayerSelection.bind(this, metadata.gameID)}, selections);

        joinAccepts.push(<span>Someone joined your game: </span>);
        joinAccepts.push(selector);
        if(selectedPlayer !== null && gameAuth !== null) {
          joinAccepts.push(React.createElement(
            "button",
            {key: "acceptButton_"+metadata.gameID, className: "yes", onClick: this.acceptUserButtonClicked.bind(this, metadata.gameID, gameAuth, userinfo.name)},
            "Play " + selectedPlayer
          ));
          joinAccepts.push(React.createElement(
            "button",
            {key: "declineButton_"+metadata.gameID, className: "no", onClick: this.declineUserButtonClicked.bind(this, metadata.gameID, gameAuth, userinfo.name)},
            "Decline " + selectedPlayer
          ));
        }
        else {
          joinAccepts.push(React.createElement(
            "button",
            {key: "acceptButton_"+metadata.gameID, className: "yes", disabled: true, onClick: function() {return false;}},
            "Play"
          ));
          joinAccepts.push(React.createElement(
            "button",
            {key: "declineButton_"+metadata.gameID, className: "no", disabled: true, onClick: function() {return false;}},
            "Decline"
          ));
        }
      }

      if(joined) {
      }
    }
    if(metadata.activeGameData !== undefined) {
      if(metadata.gUser.name == username || metadata.sUser.name == username)
        gameButton.push(React.createElement("button", {key: "gameButton_"+metadata.gameID, className: "goToOurGame", onClick: this.gameButtonClicked.bind(this,metadata.gameID)}, "Go to My Game"));
      else
        gameButton.push(React.createElement("button", {key: "gameButton_"+metadata.gameID, className: "gameButton", onClick: this.gameButtonClicked.bind(this,metadata.gameID)}, "Watch Game"));
    }

    var elts = [];

    elts.push(React.createElement("div", {key: "title_"+metadata.gameID}, title));
    elts.push(React.createElement("div", {key: "info_"+metadata.gameID}, info));
    elts.push(React.createElement("div", {key: "joinAccepts_"+metadata.gameID},joinAccepts));
    if(gameButton.length > 0)
      elts.push(React.createElement("div", {key: "gameButtonDiv_"+metadata.gameID},gameButton));

    var classes = ClassNames({
      "gameroomGameElt": true,
      "quickHighlightOrange": metadata.gameID in this.state.recentHighlightGameIDs,
      "quickHighlightGreen": metadata.gameID in this.state.recentPlayingGameIDs
    });

    return React.createElement("div", {key: "main_"+metadata.gameID, className:classes}, elts);
  },

  renderUser: function(userInfo) {
    return React.createElement("div", {key: "users_"+userInfo.name}, Utils.userDisplayStr(userInfo));
  },

  renderNotification: function(i,msg) {
    return React.createElement("li", {key: "notification_"+i}, msg);
  },

  render: function() {
    var that = this;
    var username = UserStore.getUsername();

    var createModal = (
        <Modal isOpen={this.state.createGameDialogOpen} onRequestClose={this.closeCreateDialog}>
        <CreateGameDialog handleSubmitted={this.handleCreateGameSubmitted} handleCancelled={this.handleCreateGameCancelled}/>
        </Modal>
    );

    var popupModalStyle = {
      content: { width: "500px", height:"120px"}
    };
    var popupModal = (
        <Modal isOpen={this.state.popupMessageOpen} onRequestClose={this.closePopupMessage} style={popupModalStyle}>
        <InfoDialog message={this.state.popupMessage} handleOk={this.handlePopupOk}/>
        </Modal>
    );

    var errorDiv = "";
    if(this.state.error != "") {
      errorDiv = React.createElement("div", {key: "errorDiv", className:"bigError bMargin"}, this.state.error);
    }

    var usersDiv = "";
    {
      var usersList = this.state.usersLoggedIn.map(function(user) {
        return that.renderUser(user);
      });
      usersDiv = React.createElement("div", {key: "usersDiv", className:"gameroomUsersDiv"}, usersList);
      usersDiv = React.createElement("div", {key: "usersLabeledDiv", className:"gameroomLabeledUsersDiv uiPanel"}, [
        React.createElement("h4", {key: "usersLoggedInLabel"}, "Users Logged In:"),
        usersDiv
      ]);
    }

    var ownGamesDiv = "";
    {
      var ownGamesList = this.state.ownGames.map(function(metadata) {
        return that.renderGame(metadata);
      });

      ownGamesList.unshift(React.createElement("button", {key: "createGameButton", className:"bMargin", onClick: this.createButtonClicked}, "Create New Game"));
      ownGamesList.unshift(React.createElement("h3", {key: "myCurrentGamesLabel"}, "My Current Games"));
      ownGamesDiv = React.createElement("div", {key: "ownDiv", className:"uiPanel bMargin"}, ownGamesList);
    }

    var joinableOpenGamesDiv = "";
    {
      var joinableOpenGamesList = this.state.joinableOpenGames.map(function(metadata) {
        return that.renderGame(metadata);
      });

      joinableOpenGamesList.unshift(React.createElement("h3", {key: "openGamesLabel"}, "Open Games"));
      joinableOpenGamesDiv = React.createElement("div", {key: "joinableOpenDiv", className:"uiPanel bMargin" }, joinableOpenGamesList);
    }


    var watchableGamesDiv = "";
    {
      var watchableGamesList = this.state.watchableGames.map(function(metadata) {
        return that.renderGame(metadata);
      });

      watchableGamesList.unshift(React.createElement("h3", {key: "activeGamesLabel"}, "Active Games"));
      watchableGamesDiv = React.createElement("div", {key: "watchableDiv", className:"uiPanel bMargin"}, watchableGamesList);
    }

    var chat = (
        <Chat params={{chatChannel:"main"}}/>
    );

    var notificationsDiv = "";
    if(this.state.notifications.length > 0) {
      var notificationsList = this.state.notifications.map (function (msg,i) {
        return that.renderNotification(i,msg);
      });
      notificationsDiv =
        React.createElement("div", {key: "notificationsDiv", className:"uiPanel bMargin"},
          React.createElement("ul", {key: "notificationsList"}, notificationsList));
    }

    var contents = [
      createModal,
      popupModal,
      notificationsDiv,
      React.createElement("div", {key:"gameroomPanels", className: "gameroomPanels"}, [
        React.createElement("div", {key:"gamesDiv", className:"games"}, [
          React.createElement("h1", {key:"gameroomTitle"}, "Arimaa Gameroom"),
          errorDiv,
          React.createElement("div", {key:"gamesContentsDiv", className:"gamesContents"}, [
            React.createElement("div", {key:"gamesListsDiv", className:"gamesLists"}, [
              ownGamesDiv,
              joinableOpenGamesDiv,
              watchableGamesDiv
            ]),
            usersDiv
          ]),
        ]),
        chat
      ]),
    ];

    return React.createElement("div", {key:"gameroomContents", className: "gameroom"}, contents);

  }

});

module.exports = component;
