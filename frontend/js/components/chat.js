var React = require('react');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var chatBox = React.createClass({
  getInitialState: function() {
    var ws = APIUtils.chatSocket(this.props.chatChannel);
    ws.onopen = this.onWSOpen;
    ws.onclose = this.onWSClose;
    ws.onmessage = this.onWSMessage;
    ws.onerror = this.onWSError;
    return {lines:[], chatAuth:null, userInput:"", ws:ws, wsOpen:false};
  },
  handleUserInputChange: function(e) {
    this.setState({userInput: e.target.value});
  },

  doJoin: function() {
    if(this.state.wsOpen) {
      this.state.ws.send(JSON.stringify({action:"join",text:UserStore.siteAuthToken()}));
    }
  },

  submitJoin: function(event) {
    event.preventDefault();
    this.doJoin();
  },

  submitUserInput: function(event) {
    event.preventDefault();
    if(this.state.wsOpen && this.state.chatAuth) {
      var userInput = this.state.userInput.trim();
      this.setState({userInput:""});
      if(userInput.length > 0) {
        this.state.ws.send(JSON.stringify({action:"post",text:userInput}));
      }
    }
  },

  submitClose: function(event) {
    event.preventDefault();
    this.state.ws.close();
  },

  onWSOpen: function () {
    this.setState({wsOpen:true});
    this.doJoin();
  },

  onWSClose: function () {
    this.setState({wsOpen:false, chatAuth:null});
  },

  onWSMessage: function (wsmessage) {
    var data = wsmessage.data.trim();
    if(data.length > 0) {
      var message = JSON.parse(data);
      if(message.error) {
        //TODO
        console.log(message.error);
      }
      else if(message.chatAuth) {
        this.setState({chatAuth:message.chatAuth});
      }
      else if(message.id) {
        this.setState({lines: this.state.lines.concat([message])});
      }
    }
  },

  onWSError: function (error) {
    //TODO
    console.log(error);
  },

  render: function() {
    //TODO
    if(!this.state.chatAuth) {
      // return(
      //   React.createElement("div", {className: "loginBox"},
      //     React.createElement("h1", null, "Login"),
      //     React.createElement("form", {className: "commentForm", onSubmit: this.joinChatRoom},
      //       React.createElement("input", {type: "text", ref: "username", placeholder: "Username"}),
      //       React.createElement("input", {type: "submit", value: "Join"})
      //     )
      //   )
      // )
    }

    var lines = this.state.lines.map(function(line) {
      return React.createElement("tr", {key: line.id}, React.createElement("td", {key: line.id}, React.createElement("b", null, line.username + ": "), line.text));
    });

    return (
      React.createElement("div", {className: "commentBox"},
        React.createElement("h1", null, "Chat"),
        React.createElement("table", null,
          lines
        ),
        React.createElement("form", {className: "commentForm", onSubmit: this.submitUserInput},
          React.createElement("input", {type: "text", ref: "text", value: this.state.userInput, onChange: this.handleUserInputChange, placeholder: "Say something..."}),
          React.createElement("input", {type: "submit", disabled: !this.state.chatAuth, value: "Post"})
        ),
        React.createElement("button", {onClick: this.submitJoin, disabled: !(!this.state.chatAuth)}, "Join"),
        React.createElement("button", {onClick: this.submitClose, disabled: !this.state.chatAuth}, "Leave")
      )
    );
  }


  // render: function() {
  //   //var value = this.state.value;
  //   //return <input type="text" value={value} onChange={this.handleChange} />;

  //   var errorText = null;
  //   var messageText = null;
  //   //is empty string falsey?
  //   if(this.state.error != "") {
  //     errorText = (<div className="error">{this.state.error}</div>);
  //   }
  //   var messageText = null;
  //   //TODO it's weird to use the "forgotpass" class for the div
  //   if(this.state.message != "") {
  //     messageText = (<div className="forgotpass">{this.state.message}</div>);
  //   }

  //   //TODO here and elsewhere, should we be disabling the buttons upon submit so that we don't accidentally get double-sends of requests?
  //   //TODO it's weird to use the "forgotpass" class for the div
  //   return (
  //     <div>
  //       <div className="login">
  //         <h1>Reset Password</h1>
  //         <form method="post" action="index.html">
  //           <input type="password" name="password" value={this.state.pass} onChange={this.handlePasswordChange} placeholder="New Password"/>
  //           <input type="password" name="confirmPassword" value={this.state.confirmPass} onChange={this.handleConfirmPasswordChange} placeholder="Confirm New Password"/>
  //           <input type="submit" className="submit" name="commit" value="Set New Password" onClick={this.submitResetPassword}/>
  //         </form>
  //         {errorText}
  //         <div className="forgotpass"><a href="/login">Back to login</a></div>
  //       </div>
  //     </div>
  //   )
  // }
});

module.exports = chatBox;
