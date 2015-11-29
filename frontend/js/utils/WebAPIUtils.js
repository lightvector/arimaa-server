//we're only using jquery to do ajax, so
//it might be better to use a lighter library for that
var $ = require('jquery');
var UserStore = require('../stores/UserStore.js');

var PRINT_DATA = true;

function POST(url, data, success, error) {
  $.ajax({
    type: 'POST',
    url: url,
    contentType: 'application/json; charset=utf-8',
    data: JSON.stringify(data),
    success: function(received, textStatus, xhr) {
      if(PRINT_DATA) { console.log("url: " + url + "\ndata: " + data + "\nreceived: ", received);}

      if('error' in received) {
        error(received);
      } else {
        success(received);
      }
    },
    error: function(xhr, textStatus, err) {
      if(PRINT_DATA) console.log(err);
      if(xhr.status === 400) {
        //authentication fail
        console.log('auth fail');
        //logout();
      } else {
        error({error: err});
      }
    }
  });
}

function GET(url, data, success, error) {
  $.ajax({
    type: 'GET',
    url: url,
    contentType: 'application/json; charset-utf-8',
    data: data,
    success: function(received, textStatus, xhr) {
      if(PRINT_DATA) {console.log("url: " + url + "\ndata: " + data + "\nreceived: ", received);}
      if('error' in received) {
        error(received);
      } else {
        success(received);
      }
    },
    error: function(xhr, textStatus, err) {
      if(PRINT_DATA) console.log(err);
      if(xhr.status === 400) {
        //authentication fail
        console.log('auth fail');
        //logout();
      } else {
        error({error: err});
      }
    }
  });
}

function websocketURL(path) {
  var l = window.location;
  return ((l.protocol === "https:") ? "wss://" : "ws://") + l.hostname + (((l.port != 80) && (l.port != 443)) ? ":" + l.port : "") + path;
}

var APIUtils = {
  register_bot: function(username, email, password) {
    //TODO: NOT YET IMPLEMENTED!
  },

  login: function(username, password, success, error) {
    POST('/api/accounts/login', {username:username, password:password}, success, error);
  },

  loginGuest: function(username, success, error) {
    POST('/api/accounts/loginGuest', {username:username}, success, error);
  },

  register: function(username, email, password, success, error) {
    POST('/api/accounts/register', {username:username, email:email, password:password, isBot:false}, success, error);
  },

  logout: function(success, error) {
    POST('/api/accounts/logout', {siteAuth:UserStore.siteAuthToken()}, success, error);
  },

  authLoggedIn: function(success, error) {
    POST('/api/accounts/authLoggedIn', {siteAuth:UserStore.siteAuthToken()}, success, error);
  },

  usersLoggedIn: function(success, error) {
    POST('/api/accounts/usersLoggedIn', {}, success, error);
  },

  forgotPassword: function(username, success, error) {
    POST('/api/accounts/forgotPassword', {username:username}, success, error);
  },

  resetPassword: function(username, resetAuth, password, success, error) {
    POST('/api/accounts/resetPassword', {username:username, resetAuth:resetAuth, password:password}, success, error);
  },

  //TODO use this
  changePassword: function(username, password, siteAuth, newPassword, success, error) {
    POST('/api/accounts/changePassword', {username:username, password:password, siteAuth:UserStore.siteAuthToken(), newPassword:newPassword}, success, error);
  },

  //TODO use this
  changeEmail: function(username, password, siteAuth, newEmail, success, error) {
    POST('/api/accounts/changeEmail', {username:username, password:password, siteAuth:UserStore.siteAuthToken(), newEmail:newEmail}, success, error);
  },

  //TODO use this
  confirmChangeEmail: function(username, changeAuth, success, error) {
    POST('/api/accounts/changeEmail', {username:username, changeAuth:changeAuth}, success, error);
  },

  createGame: function(options, success, error) {
    console.log('creating game ',options);
    POST('/api/games/actions/create', options, success, error);
  },

  joinGame: function(gameID, success, error) {
    console.log('joining game ', gameID);
    POST('/api/games/'+gameID+'/actions/join', {siteAuth:UserStore.siteAuthToken()}, success, error);
  },

  leaveGame: function(gameID, gameAuth, success, error) {
    console.log('leaving game ', gameID);
    POST('/api/games/'+gameID+'/actions/leave', {gameAuth:gameAuth}, success, error);
  },

  gameState: function(gameID, seq, success, error) {
    GET('/api/games/'+gameID+'/state', {minSequence:seq}, success, error);
  },

  gameMetadata: function(gameID, seq, success, error) {
    GET('/api/games/'+gameID+'/metadata', {minSequence:seq}, success, error);
  },

  gameHeartbeat: function(gameID, gameAuth, success, error) {
    POST('/api/games/'+gameID+'/actions/heartbeat', {gameAuth:gameAuth}, success, error);
  },

  acceptUserForGame: function(gameID, gameAuth, username, success, error) {
    POST('/api/games/'+gameID+'/actions/accept', {gameAuth:gameAuth, opponent:username}, success, error);
  },
  declineUserForGame: function(gameID, gameAuth, username, success, error) {
    POST('/api/games/'+gameID+'/actions/decline', {gameAuth:gameAuth, opponent:username}, success, error);
  },

  sendMove: function(gameID, gameAuth, moveStr, plyNum, success, error) {
    POST('/api/games/'+gameID+'/actions/move', {gameAuth:gameAuth, move:moveStr, plyNum:plyNum}, success, error);
  },

  getOpenGames: function(success, error) {
    GET('/api/games/search',{open:true}, success, error);
  },

  getActiveGames: function(success, error) {
    GET('/api/games/search',{active:true}, success, error);
  },

  chatSocket: function(chatChannel) {
    return new WebSocket(websocketURL('/api/chat/'+chatChannel+'/socket'));
  },

  chatJoin: function(chatChannel, success, error) {
    POST('/api/chat/'+chatChannel+'/join', {siteAuth:UserStore.siteAuthToken()}, success, error);
  },

  chatLeave: function(chatChannel, chatAuth, success, error) {
    POST('/api/chat/'+chatChannel+'/leave', {chatAuth:chatAuth}, success, error);
  },

  chatHeartbeat: function(chatChannel, chatAuth, success, error) {
    POST('/api/chat/'+chatChannel+'/heartbeat', {chatAuth:chatAuth}, success, error);
  },

  chatPost: function(chatChannel, chatAuth, text, success, error) {
    POST('/api/chat/'+chatChannel+'/post', {chatAuth:chatAuth, text:text}, success, error);
  },

  chatGet: function(chatChannel, success, error) {
    GET('/api/chat/'+chatChannel, {}, success, error);
  },

  chatPoll: function(chatChannel, minId, success, error) {
    GET('/api/chat/'+chatChannel, {minId:minId, doWait:true}, success, error);
  }

};


module.exports = APIUtils;
