var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var component = React.createClass({
  getInitialState: function() {
    return {error:'', ownGames:[], joinableOpenGames:[], watchableGames:[]};
  },

  componentDidMount: function() {
    UserStore.addChangeListener(this._onChange);
    UserStore.addGameMetaChangeListener(this._onGameMetaChange);
    SiteActions.beginOpenGamesLoop();
    SiteActions.startOpenJoinedHeartbeatLoop();
  },
  componentWillUnmount: function() {
    UserStore.removeChangeListener(this._onChange);
    UserStore.removeChangeListener(this._onGameMetaChange);
  },

  _onGameMetaChange: function() {
    this.setState({
      ownGames:UserStore.getOwnGames(),
      joinableOpenGames:UserStore.getJoinableOpenGames(),
      watchableGames:UserStore.getWatchableGames()
    });
  },

  _onChange: function() {

  },


  //TODO
  joinGameButtonClicked: function(gameID, evt) {
    // evt.target.disabled = true;
    // evt.target.innerHTML = "joined";
    // SiteActions.joinGame(gameID);
  },

  acceptUserButtonClicked: function(gameID, username, evt) {
    // evt.target.disabled = true;
    // SiteActions.acceptUserForGame(gameID, username);
  },

  goToGameButtonClicked: function(gameID) {
    // window.location.pathname = "/game/" + gameID;
  },


  gameTitle: function(metadata) {
    var title = "";
    var hasCreator = metadata.openGameData !== undefined && metadata.openGameData.creator !== undefined;

    //TODO ban "anyone" or super-short usernames as usernames??
    if(metadata.gUser !== undefined && metadata.sUser !== undefined) title = metadata.gUser + " vs " + metadata.sUser;
    else if(metadata.gUser !== undefined && hasCreator && metadata.openGameData.creator != metadata.gUser) title = metadata.gUser + " vs " + metadata.openGameData.creator;
    else if(metadata.sUser !== undefined && hasCreator && metadata.openGameData.creator != metadata.sUser) title = metadata.openGameData.creator + " vs " + metadata.sUser;
    else if(metadata.gUser !== undefined) title = metadata.gUser + " vs " + "anyone";
    else if(metadata.sUser !== undefined) title = "anyone" + " vs " + metadata.sUser;
    else title = "Open game";

    if(metadata.tags.length > 0)
      title = title + " (" + metadata.tags.join(", ") + ")";
    return title;
  },

  gameSpanString: function(seconds) {
    var s = "";
    if(seconds >= 86400) { s += Math.floor(seconds/86400) + "d"; seconds = seconds % 86400;}
    if(seconds >= 3600)  { s += Math.floor(seconds/3600)  + "h"; seconds = seconds % 3600;}
    if(seconds >= 60)    { s += Math.floor(seconds/60)    + "m"; seconds = seconds % 60;}
    if(seconds >= 0.5)   { s += Math.round(seconds)       + "s"; seconds = 0;}
    return s;
  },

  gameTCString: function(tc) {
    var s = "";
    s += this.gameSpanString(tc.initialTime);
    if(tc.increment !== undefined && tc.increment > 0) s += "+" + this.gameSpanString(tc.increment);
    if(tc.delay !== undefined && tc.delay > 0) s += "~" + this.gameSpanString(tc.delay);
    if(tc.maxReserve !== undefined) s += "(" + this.gameSpanString(tc.maxReserve) + ")";
    if(tc.maxMoveTime !== undefined) s += "(" + this.gameSpanString(tc.maxMaxMoveTime) + " max/mv)";
    if(tc.overtimeAfter !== undefined) s += "(max " + tc.overtimeAfter + "t)";
    return s
  },

  gameInfoString: function(metadata) {
    var infos = [];

    infos.push(metadata.gameType);

    if(metadata.rated)
      infos.push("rated");
    else
      infos.push("unrated");

    if(metadata.postal)
      infos.push("postal");

    if(metadata.result !== undefined)
      infos.push(metadata.result.winner + "+" + metadata.result.reason);

    var gTC = this.gameTCString(metadata.gTC);
    var sTC = this.gameTCString(metadata.sTC);
    if(gTC != sTC) {
      infos.push("G:"+gTC);
      infos.push("S:"+sTC);
    }
    else
      infos.push("TC:"+gTC);

    infos.push("move " + (Math.floor((metadata.numPly+2)/2)) + (metadata.numPly % 2 == 0 ? "g" : "s"));

    return infos.join(", ");
  },

  render: function() {
    var that = this;
    var username = UserStore.getUsername();

    var ownGamesDiv = "";
    if(this.state.ownGames.length > 0) {
      var ownGamesList = this.state.ownGames.map(function(metadata) {
        var title = that.gameTitle(metadata);
        var info = that.gameInfoString(metadata);
        var elts = [];
        var buttons = [];

        var joined = false;
        for(var j = 0; j<metadata.openGameData.joined.length; j++) {
          if(metadata.openGameData.joined[j].name == username) {
            joined = true; break;
          }
        }
        //TODO add a close button for games that you're a creator of
        //TODO figure out why heartbeats are getting errors

        if(joined)
          buttons.push(<button disabled="true" onClick={that.joinGameButtonClicked.bind(that, metadata.id)}>Joined</button>);
        else
          buttons.push(<button onClick={that.joinGameButtonClicked.bind(that, metadata.id)}>Join</button>);

        for(var i = 0; i<metadata.openGameData.joined.length; i++) {
          var userinfo = metadata.openGameData.joined[i];
          if(userinfo.name != username) {
            buttons.push(<button onClick={that.acceptUserButtonClicked.bind(that, metadata.id, userinfo.name)}>Accept {userinfo.name}</button>);
          }
        }
        // <button onClick={that.goToGameButtonClicked.bind(that, metadata.id)}>Go To Game</button>

        elts.push(React.createElement("div", {key: "title_"+metadata.id}, title));
        elts.push(React.createElement("div", {key: "info_"+metadata.id}, info));
        elts.push(React.createElement("div", {key: "buttons_"+metadata.id},buttons));

        return React.createElement("div", {key: "main_"+metadata.id}, elts);
      });

      ownGamesList.unshift(React.createElement("h3", {}, "My Current Games"));
      ownGamesDiv = React.createElement("div", {key: "ownDiv"}, ownGamesList);
    }

    joinableOpenGamesDiv = "";
    // if(this.state.joinableOpenGames.length > 0) {
    {
      var joinableOpenGamesList = this.state.joinableOpenGames.map(function(metadata) {
        var title = that.gameTitle(metadata);
        var info = that.gameInfoString(metadata);
        var elts = [];
        var buttons = [];

        var joined = false;
        for(var j = 0; j<metadata.openGameData.joined.length; j++) {
          if(metadata.openGameData.joined[j].name == username) {
            joined = true; break;
          }
        }

        if(joined)
          buttons.push(<button disabled="true" onClick={that.joinGameButtonClicked.bind(that, metadata.id)}>Joined</button>);
        else
          buttons.push(<button onClick={that.joinGameButtonClicked.bind(that, metadata.id)}>Join</button>);

        elts.push(React.createElement("div", {key: "title_"+metadata.id}, title));
        elts.push(React.createElement("div", {key: "info_"+metadata.id}, info));
        elts.push(React.createElement("div", {key: "buttons_"+metadata.id},buttons));

        return React.createElement("div", {key: "main_"+metadata.id}, elts);
      });

      joinableOpenGamesList.unshift(React.createElement("h3", {}, "Open Games"));
      joinableOpenGamesDiv = React.createElement("div", {key: "joinableOpenDiv"}, joinableOpenGamesList);
    }


    watchableGamesDiv = "";
    // if(this.state.watchableGames.length > 0) {
    {
      var watchableGamesList = this.state.watchableGames.map(function(metadata) {
        var title = that.gameTitle(metadata);
        var info = that.gameInfoString(metadata);
        var elts = [];
        var buttons = [];

        elts.push(React.createElement("div", {key: "title_"+metadata.id}, title));
        elts.push(React.createElement("div", {key: "info_"+metadata.id}, info));
        elts.push(React.createElement("div", {key: "buttons_"+metadata.id},buttons));

        return React.createElement("div", {key: "main_"+metadata.id}, elts);
      });

      watchableGamesList.unshift(React.createElement("h3", {}, "Active Games"));
      watchableGamesDiv = React.createElement("div", {key: "watchableDiv"}, watchableGamesList);
    }

    var contents = [
      React.createElement("h2", {}, "Arimaa Gameroom"),
      ownGamesDiv,
      joinableOpenGamesDiv,
      watchableGamesDiv
    ];
    // if(this.state.error != "") {
    //   contents.push(React.createElement("div", {className:"error"}, this.state.error));
    // }

    return React.createElement("div", {className: "commentBox"}, contents);

  }

});

module.exports = component;
