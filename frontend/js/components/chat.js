var React = require('react');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var SiteConstants = require('../constants/SiteConstants.js');
var Utils = require('../utils/Utils.js');

const FUNC_NOP = function(){};

var chatBox = React.createClass({
  getInitialState: function() {
    return {lines:[], nextMinId:-1, chatAuth:null, userInput:"", error:""};
  },
  componentDidMount: function() {
    this.doJoin();
  },

  clearError: function() {
    this.setState({error:""});
  },

  handleUserInputChange: function(e) {
    this.setState({userInput: e.target.value});
    this.clearError();
  },
  submitUserInput: function(event) {
    event.preventDefault();
    this.clearError();
    if(this.state.chatAuth !== null) {
      var userInput = this.state.userInput.trim();
      this.setState({userInput:""});
      if(userInput.length > 0) {
        APIUtils.chatPost(this.props.params.chatChannel, this.state.chatAuth, userInput, FUNC_NOP, this.onUserInputError);
      }
    }
  },
  onUserInputError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },

  doJoin: function() {
    APIUtils.chatJoin(this.props.params.chatChannel, this.onJoinSuccess, this.onJoinError);
  },
  onJoinSuccess: function(data) {
    var chatAuth = data.chatAuth;
    var that = this;
    this.setState({chatAuth:chatAuth}, function() { that.startPollLoop(chatAuth); that.startHeartbeatLoop(chatAuth); });
  },
  onJoinError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },
  submitJoin: function(event) {
    event.preventDefault();
    this.clearError();
    this.doJoin();
  },

  doLeave: function() {
    if(this.state.chatAuth !== null) {
      APIUtils.chatLeave(this.props.params.chatChannel, this.state.chatAuth, this.onLeaveSuccess, this.onLeaveError);
    }
  },
  onLeaveSuccess: function(data) {
    this.setState({chatAuth:null});
  },
  onLeaveError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },
  submitLeave: function(event) {
    event.preventDefault();
    this.clearError();
    this.doLeave();
  },

  startHeartbeatLoop: function(chatAuth) {
    if(this.state.chatAuth !== null && this.state.chatAuth == chatAuth) {
      APIUtils.chatHeartbeat(this.props.params.chatChannel, chatAuth, FUNC_NOP, this.onHeartbeatError);
      var that = this;
      setTimeout(function () {that.startHeartbeatLoop(chatAuth);}, SiteConstants.VALUES.CHAT_HEARTBEAT_PERIOD * 1000);
    }
  },
  onHeartbeatError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },

  startPollLoop: function(chatAuth) {
    if(this.state.chatAuth !== null && this.state.chatAuth == chatAuth) {
      var that = this;
      if(this.state.nextMinId < 0)
        APIUtils.chatGet(this.props.params.chatChannel,
                         function(data) {that.onLinesSuccess(data,chatAuth);}, function(data) {that.onLinesError(data,chatAuth);});
      else
        APIUtils.chatPoll(this.props.params.chatChannel, this.state.nextMinId,
                          function(data) {that.onLinesSuccess(data,chatAuth);}, function(data) {that.onLinesError(data,chatAuth);});
    }
  },
  onLinesSuccess: function(data, chatAuth) {
    if(this.state.chatAuth !== null && this.state.chatAuth == chatAuth) {
      var nextMinId = this.state.nextMinId;
      for(var i = 0; i<data.lines.length; i++) {
        if(data.lines[i].id >= nextMinId)
          nextMinId = data.lines[i].id + 1;
      }
      if(nextMinId < 0)
        nextMinId = 0;

      this.setState({lines:this.state.lines.concat(data.lines).slice(-SiteConstants.VALUES.CHAT_MAX_HISTORY_LINES), nextMinId:nextMinId});
      var that = this;
      setTimeout(function () {that.startPollLoop(chatAuth);}, SiteConstants.VALUES.CHAT_LOOP_DELAY * 1000);
    }
  },
  onLinesError: function(data, chatAuth) {
    this.setState({error:data.error});
    var that = this;
    setTimeout(function () {that.startPollLoop(chatAuth);}, SiteConstants.VALUES.CHAT_LOOP_DELAY_ON_ERROR * 1000);
  },

  renderUser: function(userInfo) {
    return React.createElement("div", {key: "chatUsers_"+userInfo.name}, Utils.userDisplayStr(userInfo));
  },

  render: function() {
    var lines = this.state.lines.map(function(line) {
      var lineContents = [
        Utils.timeToHHMMSS(line.timestamp) + " ",
        React.createElement("b", {key: "chatLineName_"+line.id}, line.username + ": "),
        line.text
      ];
      return React.createElement("tr", {key: "chatLine_"+line.id}, React.createElement("td", {key: "chatLineTD_"+line.id}, lineContents));
    });

    var usersDiv = "";
    {
      var usersList = this.state.usersLoggedIn.map(function(user) {
        return that.renderUser(user);
      });
      usersList.unshift(React.createElement("h4", {key: "chatUsersLoggedInLabel"}, "Users In Chatroom:"));
      usersDiv = React.createElement("div", {key: "chatUsersDiv", className:"gameroomUsersDiv"}, usersList);
    }

    var contents = [
      React.createElement("h1", {key: "chatLabel"}, "Chat"),
      React.createElement(
        "table", null,
        React.createElement("tbody", {key: "chatBody"}, lines)
      ),
      React.createElement(
        "form", {className: "commentForm", onSubmit: this.submitUserInput},
        React.createElement("input", {key:"chatInput", type: "text", ref: "text", value: this.state.userInput, onChange: this.handleUserInputChange, placeholder: "Say something..."}),
        React.createElement("input", {key:"chatSubmit", type: "submit", disabled: !this.state.chatAuth, value: "Post"})
      ),
      usersDiv,
      React.createElement("button", {key:"chatJoin", onClick: this.submitJoin, disabled: !(!this.state.chatAuth)}, "Join"),
      React.createElement("button", {key:"chatLeave", onClick: this.submitLeave, disabled: !this.state.chatAuth}, "Leave"),
    ];

    if(this.state.error != "") {
      contents.push(React.createElement("div", {key: "chatError", className:"error"}, this.state.error));
    }

    return React.createElement("div", {key: "chatContainer", className: "commentBox"}, contents);
  }

});

module.exports = chatBox;
