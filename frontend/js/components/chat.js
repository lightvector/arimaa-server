var React = require('react');
var ReactDOM = require('react-dom');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var SiteConstants = require('../constants/SiteConstants.js');
var Utils = require('../utils/Utils.js');

const FUNC_NOP = function(){};

var chatBox = React.createClass({
  getInitialState: function() {
    return {lines:[], nextMinId:-1, chatAuth:null, userInput:"", usersLoggedIn:[], inputDisabled:false, error:""};
  },
  componentDidMount: function() {
    this.doJoin();
  },

  clearError: function() {
    this.setState({error:""});
  },

  //Manually reach into the dom and make the chat scroll to the bottom on update if it's at that point just prior to the update
  componentWillUpdate: function() {
    var chatTable = ReactDOM.findDOMNode(this.refs.chatTable);
    chatTable.shouldScrollBottom = chatTable.scrollTop + chatTable.offsetHeight === chatTable.scrollHeight;
  },
  componentDidUpdate: function() {
    var chatTable = ReactDOM.findDOMNode(this.refs.chatTable);
    if(chatTable.shouldScrollBottom) {
      chatTable.scrollTop = chatTable.scrollHeight;
    }
  },
  
  handleUserInputChange: function(e) {
    this.setState({userInput: e.target.value});
  },
  submitUserInput: function(event) {
    event.preventDefault();
    if(this.state.chatAuth !== null && !this.state.inputDisabled) {
      this.clearError();
      var userInput = this.state.userInput.trim();
      if(userInput.length > 0) {
        this.setState({inputDisabled:true});
        APIUtils.chatPost(this.props.params.chatChannel, this.state.chatAuth, userInput, this.onSubmitSuccess, this.onSubmitError);
      }
    }
  },
  onSubmitSuccess: function(data) {
    this.setState({userInput:""});
    this.setState({inputDisabled:false});
    var chatTable = ReactDOM.findDOMNode(this.refs.chatTable);
    chatTable.scrollTop = chatTable.scrollHeight;
    ReactDOM.findDOMNode(this.refs.text).focus(); 
  },
  onSubmitError: function(data) {
    this.setState({inputDisabled:false});
    this.setState({error:data.error});
    ReactDOM.findDOMNode(this.refs.text).focus(); 
  },

  doJoin: function() {
    APIUtils.chatJoin(this.props.params.chatChannel, this.onJoinSuccess, this.onJoinError);
  },
  onJoinSuccess: function(data) {
    var chatAuth = data.chatAuth;
    var that = this;
    this.setState({inputDisabled:false});
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
    this.setState({inputDisabled:false});
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
      APIUtils.chatUsersLoggedIn(this.props.params.chatChannel, this.onUsersLoggedIn, this.onUsersLoggedInError);
      var that = this;
      setTimeout(function () {that.startHeartbeatLoop(chatAuth);}, SiteConstants.VALUES.CHAT_HEARTBEAT_PERIOD * 1000);
    }
  },
  onHeartbeatError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },
  onUsersLoggedInError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },
  onUsersLoggedIn: function(data) {
    this.setState({usersLoggedIn:data.users});
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
  renderNotLoggedInUsers: function() {
    return React.createElement("div", {key: "chatUsers_null"}, "(not logged in)");
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
    if(lines.length == 0) {
      lines = [
        React.createElement("tr", {key: "chatLine_null"}, React.createElement("td", {key: "chatLineTD_null"}, "(no comments/chat yet)"))
      ];
    }

    var usersDiv = "";
    {
      var that = this;
      var usersList;
      if(this.state.chatAuth === null)
        usersList = [ this.renderNotLoggedInUsers() ];
      else
        usersList = this.state.usersLoggedIn.map(function(user) {
          return that.renderUser(user);
        });
      
      usersDiv = React.createElement("div", {key: "chatUsersDiv", className:"chatUsersDiv"}, usersList);
      usersDiv = React.createElement("div", {key: "chatLabeledUsersDiv", className:"chatLabeledUsersDiv uiPanel"}, [
        React.createElement("h4", {key: "chatUsersLoggedInLabel"}, "Users In Chatroom:"),
        usersDiv
      ]);
    }

    var chatLabel =
      this.props.params.chatChannel == "main" ?
      React.createElement("h1", {key: "chatLabel"}, "Chat") :
      React.createElement("h1", {key: "chatLabel"}, "In-game Chat");

    var contents = [
      chatLabel,
      React.createElement("div", {key: "chatContents", className:"chatContents"}, [
        React.createElement("div", {key: "chatUI", className:"chatUI uiPanel"}, [
          React.createElement(
            "table", {key: "chatTable", ref:"chatTable", className:"chatTable"},
            React.createElement("tbody", {key: "chatBody", className: "chatBody"}, lines)
          ),
          React.createElement(
            "form", {key: "chatForm", className: "chatForm", onSubmit: this.submitUserInput},
            React.createElement("input", {key:"chatInput", type: "text", ref: "text", value: this.state.userInput, onChange: this.handleUserInputChange, disabled: this.state.inputDisabled, placeholder: "Say something..."}),
            React.createElement("input", {key:"chatSubmit", type: "submit", className:"submit", disabled: !this.state.chatAuth || this.state.inputDisabled, value: "Post"})
          ),
          React.createElement("div", {key: "chatJoinLeaveDiv", className: "chatJoinLeaveDiv"}, [
            React.createElement("button", {key:"chatJoin", onClick: this.submitJoin, disabled: !(!this.state.chatAuth)}, "Join"),
            React.createElement("button", {key:"chatLeave", onClick: this.submitLeave, disabled: !this.state.chatAuth}, "Leave")
          ])
        ]),
        usersDiv
      ]),
    ];

    if(this.state.error != "") {
      contents.push(React.createElement("div", {key: "chatError", className:"error"}, this.state.error));
    }

    return React.createElement("div", {key: "chat", className: "chat"}, contents);
  }

});

module.exports = chatBox;
