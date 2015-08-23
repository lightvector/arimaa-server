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
    POST('api/accounts/login', {username:username, password:password}, success, error);
  },

  register: function(username, email, password, success, error) {
    POST('api/accounts/register', {username:username, email:email, password:password,isBot:false}, success, error);
  },

  logout: function() {
    POST('api/accounts/logout', {siteAuth:UserStore.siteAuthToken()}, function(){}, function(){});
  },

  createGame: function(options, success, error) {
    console.log('creating game ',options);
    POST('api/games/actions/create', options, success, error);
  },

  joinGame: function(gameId, success, error) {
    console.log('joining game ', gameId);
    POST('api/games/'+gameId+'/actions/join', {siteAuth:UserStore.siteAuthToken()}, success, error);
  },

  gameStatus: function(gameId, success, error) {
    GET('api/games/'+gameId+'/state', success, error);
  },

  gameHeartbeat: function(gameId, success, error) {
    POST('api/games/'+gameId+'/heartbeat', {gameAuth:""}, success, error);
  },

  complete_move: function() {

    /*
    $.ajax({
      type : 'POST',
      url : 'api/game/12345/complete', //CHANGE THIS!!!!!!!!!!!!!!
      contentType: "application/json; charset=utf-8",
      dataType : 'json',
      data : JSON.stringify({username:this.state.username,auth:this.state.auth,text:comment}),
      success : function(data) {
        //console.log("post chat success",data);
        if(data.error) {
          alert(data.error);
        } else {
          console.log("no error");
        }
      });*/
  },
  do_something_else: 0
}


module.exports = APIUtils;
