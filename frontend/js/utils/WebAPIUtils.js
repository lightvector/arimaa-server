//we're only using jquery to do ajax, so
//it might be better to use a lighter library for that
var $ = require('jquery');
var UserStore = require('../stores/UserStore.js')

var PRINT_DATA = true;

function POST(url, data, success, error) {
  $.ajax({
    type: 'POST',
    url: url,
    contentType: 'application/json; charset=utf-8',
    data: JSON.stringify(data),
    success: function(data, textStatus, xhr) {
      if(PRINT_DATA) { console.log("url: " + url + "\ndata: ", data);}

      if(data['error']) {
        error(data);
      } else {
        success(data);
      }
    },
    error: function(xhr, textStatus, err) {
      if(PRINT_DATA) console.log(err);
      if(xhr.status === 400) {
        //authentication fail
        console.log('auth fail');
        //logout();
      } else {
        error(err);
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
    success: function(data, textStatus, xhr) {
      if(PRINT_DATA) {console.log("url: " + url + "\ndata: ", data);}
      if(data['error']) {
        error(data);
      } else {
        success(data);
      }
    },
    error: function(xhr, textStatus, err) {
      if(PRINT_DATA) console.log(err);
      if(xhr.status === 400) {
        //authentication fail
        console.log('auth fail');
        //logout();
      } else {
        error(err);
      }
    }
  });
}


var APIUtils = {
  register_bot: function(username, email, password) {
    //NOT YET IMPLEMENTED!
  },

  login: function(username, password, success, error) {
    POST('/api/accounts/login', {username:username, password:password}, success, error);
  },

  register: function(username, email, password, success, error) {
    POST('/api/accounts/register', {username:username, email:email, password:password,isBot:false}, success, error);
  },

  logout: function() {
    POST('/api/accounts/logout', {siteAuth:UserStore.siteAuthToken()}, function(){}, function(){});
  },

  createGame: function(options, success, error) {
    console.log('creating game ',options);
    POST('/api/games/actions/create', options, success, error);
  },

  joinGame: function(gameID, success, error) {
    console.log('joining game ', gameID);
    POST('/api/games/'+gameID+'/actions/join', {siteAuth:UserStore.siteAuthToken()}, success, error);
  },

  gameStatus: function(gameID, seq, success, error) {
    GET('/api/games/'+gameID+'/state', {minSequence:seq}, success, error);
  },

  gameHeartbeat: function(gameID, success, error) {
    POST('/api/games/'+gameID+'/heartbeat', {gameAuth:""}, success, error);
  },

  acceptUserForGame: function(gameID, username, success, error) {
    POST('/api/games/'+gameID+'/actions/accept', {gameAuth:UserStore.gameAuthToken(), opponent: username}, success, error);
  },

  send_move: function(gameID, moveStr, plyNum, success, error) {
    console.log(gameID, moveStr, plyNum);
    POST('/api/games/'+gameID+'/actions/move', {gameAuth:UserStore.gameAuthToken(), move:moveStr, plyNum:plyNum}, success, error);
  },
  do_something_else: 0
}


module.exports = APIUtils;
