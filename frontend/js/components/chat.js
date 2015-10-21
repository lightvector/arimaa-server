var React = require('react');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

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
        APIUtils.chatPost(this.props.chatChannel, this.state.chatAuth, userInput, FUNC_NOP, this.onUserInputError);
      }
    }
  },
  onUserInputError: function(data) {
    this.setState({error:data.error});
    this.setState({chatAuth:null});
  },

  doJoin: function() {
    APIUtils.chatJoin(this.props.chatChannel, this.onJoinSuccess, this.onJoinError);
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
      APIUtils.chatLeave(this.props.chatChannel, this.state.chatAuth, this.onLeaveSuccess, this.onLeaveError);
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
      APIUtils.chatHeartbeat(this.props.chatChannel, chatAuth, FUNC_NOP, this.onHeartbeatError);
      //TODO sleep 30s
      var that = this;
      setTimeout(function () {that.startHeartbeatLoop(chatAuth);}, 30000);
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
        APIUtils.chatGet(this.props.chatChannel,
                         function(data) {that.onLinesSuccess(data,chatAuth);}, function(data) {that.onLinesError(data,chatAuth);});
      else
        APIUtils.chatPoll(this.props.chatChannel, this.state.nextMinId,
                          function(data) {that.onLinesSuccess(data,chatAuth);}, function(data) {that.onLinesError(data,chatAuth);});
    }
  },
  onLinesSuccess: function(data, chatAuth) {
    var nextMinId = this.state.nextMinId;
    for(var i = 0; i<data.lines.length; i++) {
      if(data.lines[i].id >= nextMinId)
        nextMinId = data.lines[i].id + 1;
    }
    if(nextMinId < 0)
      nextMinId = 0;

    //TODO max 10000 lines in chat history
    this.setState({lines:this.state.lines.concat(data.lines).slice(-10000), nextMinId:nextMinId});
    //TODO sleep 300 ms
    var that = this;
    setTimeout(function () {that.startPollLoop(chatAuth);}, 300);
  },
  onLinesError: function(data, chatAuth) {
    this.setState({error:data.error});
    var that = this;
    setTimeout(function () {that.startPollLoop(chatAuth);}, 5000); //TODO 5 second wait on error
  },

  render: function() {
    var lines = this.state.lines.map(function(line) {
      return React.createElement("tr", {key: line.id}, React.createElement("td", {key: line.id + "td"}, React.createElement("b", null, line.username + ": "), line.text));
    });

    var contents = [
      React.createElement("h1", null, "Chat"),
      React.createElement("table", null, lines),
      React.createElement(
        "form", {className: "commentForm", onSubmit: this.submitUserInput},
        React.createElement("input", {type: "text", ref: "text", value: this.state.userInput, onChange: this.handleUserInputChange, placeholder: "Say something..."}),
        React.createElement("input", {type: "submit", disabled: !this.state.chatAuth, value: "Post"})
      ),
      React.createElement("button", {onClick: this.submitJoin, disabled: !(!this.state.chatAuth)}, "Join"),
      React.createElement("button", {onClick: this.submitLeave, disabled: !this.state.chatAuth}, "Leave"),
    ];

    if(this.state.error != "") {
      contents.push(React.createElement("div", {className:"error"}, this.state.error));
    }

    return React.createElement("div", {className: "commentBox"}, contents);
  }

});

module.exports = chatBox;
