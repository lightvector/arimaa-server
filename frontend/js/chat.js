var React = require('react');
var $ = require('jquery');
var cookie = require('react-cookie');

var ChatApp = React.createClass({displayName: "ChatApp",
  handleChange: function(e) {
    this.setState({userInput: e.target.value});
  },

  handleSubmit: function(e) {
    e.preventDefault();
    var comment = React.findDOMNode(this.refs.text).value.trim();
    if(comment.length === 0) {
      alert("Comment can't be empty");
      return;
    }
    this.setState({userInput:""});

    $.ajax({
      type : 'POST',
      url : 'api/chat/main/post',
      contentType: "application/json; charset=utf-8",
      dataType : 'json',
      data : JSON.stringify({username:this.state.username,auth:this.state.auth,text:comment}),
      success : function(data) {
        //console.log("post chat success",data);
        if(data.error) {
          alert(data.error);
          this.leaveChatRoom();
        } else {
          this.loadCommentsFromServer();
        }
      }.bind(this),
      error : function(xhr, status, err) {
        console.error(this.props.url, status, err.toString());
      }.bind(this)
    });
  },

  leaveChatRoom: function() {
    this.setState({username:null,auth:null});
  },

  joinChatRoom: function(e) {
    e.preventDefault();
    var username = React.findDOMNode(this.refs.username).value.trim();

    if(username.length === 0) {
      alert("Username can't be empty");
      return;
    }

    $.ajax({
      type : 'POST',
      url: 'api/chat/main/join',
      contentType: "application/json; charset=utf-8",
      dataType : 'json',
      data : JSON.stringify({username:username}),
      success : function(data) {
        console.log("JOIN SUCCESS data: ", data);
        cookie.save('username', data.username);
        cookie.save('auth', data.auth);
        this.setState({username:data.username,auth:data.auth});
      }.bind(this),
      error : function(xhr, status, err) {
        console.error(this.props.url, status, err.toString());
      }.bind(this)
    });
  },

  loadCommentsFromServer: function() {
    $.ajax({
      url: '../mock_data/chat.json',//'api/chat/main',//
      dataType: 'json',
      cache: false,
      success: function(data) {
        this.setState({lines: data.lines});
      }.bind(this),
      error: function(xhr, status, err) {
        console.error(this.props.url, status, err.toString());
      }.bind(this)
    });
  },
  getInitialState: function() {
    return {lines:[], username:null, auth:null, userInput:""};
  },

  componentDidMount: function() {
    var username = cookie.load('username');
    var auth = cookie.load('auth');

    if(auth) this.setState({auth:auth});
    if(username) this.setState({username:username});

    console.log('user: ' + username + '\nauth: '+auth);

    this.loadCommentsFromServer();
    setInterval(this.loadCommentsFromServer, 10000);
  },
  render: function() {
    //should also catch undefined
    if(this.state.auth == null) {
      return(
        React.createElement("div", {className: "loginBox"},
          React.createElement("h1", null, "Login"),
          React.createElement("form", {className: "commentForm", onSubmit: this.joinChatRoom},
            React.createElement("input", {type: "text", ref: "username", placeholder: "Username"}),
            React.createElement("input", {type: "submit", value: "Join"})
          )
        )
      );
    }

    var lines = this.state.lines.map(function(line) {
      return React.createElement("tr", {key: line.id}, React.createElement("td", {key: line.id}, React.createElement("b", null, line.username + ": "), line.text));
    });

    return (
      React.createElement("div", {className: "commentBox"},
        React.createElement("h1", null, "Chat"),
        React.createElement("table", null,
          React.createElement("tbody", null,
            lines
          )
        ),
        React.createElement("form", {className: "commentForm", onSubmit: this.handleSubmit},
          React.createElement("input", {type: "text", ref: "text", value: this.state.userInput, onChange: this.handleChange, placeholder: "Say something..."}),

          React.createElement("input", {type: "submit", value: "Post"})
        ),
        React.createElement("button", {onClick: this.leaveChatRoom}, "Leave")
      )
    );
  }
});

ReactDOM.render(React.createElement(ChatApp, null), document.getElementById('chat_container'));
