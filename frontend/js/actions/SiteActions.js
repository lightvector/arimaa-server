var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var APIUtils = require('../utils/WebAPIUtils.js');
var SiteConstants = require('../constants/SiteConstants.js');
var UserStore = require('../stores/UserStore.js');
var cookie = require('react-cookie');

FUNC_NOP = function(){}

var SiteActions = {
  login: function(username, password) {
    APIUtils.login(username, password, this.loginSuccess, this.loginError);
  },
  loginError: function(data) {
    //console.log('error');
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.LOGIN_FAILED,
      reason: data.error
    });
  },
  loginSuccess: function(data) {
    console.log('login success');

    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    window.location.pathname = "/"; //we should track where the user was before and then redirect there instead

    //redirect here?
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.LOGIN_SUCCESS
    });
  },
  register: function(username, email, password) {
    console.log("do some registering");
    APIUtils.register(username, email, password, this.registerSuccess, this.registerError);
  },
  registerSuccess: function(data) {
    //dispatch registration success
    cookie.save('siteAuth',data.siteAuth, {path:'/'});
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.REGISTRATION_SUCCESS
    });
    window.location.pathname = "/"; //we should track where the user was before and then redirect there instead
  },
  registerError: function(data) {
    ArimaaDispatcher.dispatch({
      actionType: SiteConstants.REGISTRATION_FAILED,
      reason: data.error
    });
  },
  logout: function() {
    APIUtils.logout();
    cookie.remove('siteAuth','/');//TODO: create logout_success/error and remove cookie there
    window.location.pathname = "/login"; //make the login page nicer
  },
  createGame: function(gameType) {
    console.log("creating game with type: ", gameType);
    var opts = {
      tc: {
        initialTime: 3600 //1 hr
      },
      rated: false,
      gameType: gameType,
      siteAuth: UserStore.siteAuthToken()
    };
    APIUtils.createGame(opts, this.createGameSuccess, this.createGameError);
  },
  createGameSuccess: function(data) {
    console.log("create game data ", data);
    cookie.save('gameAuth',data.gameAuth, {path:'/'});
    APIUtils.gameStatus(data.gameID, FUNC_NOP, FUNC_NOP);
  },
  createGameError: function(data) {

  },
  joinGame: function(gameId) {
    APIUtils.joinGame(gameId, FUNC_NOP, FUNC_NOP);
  },

};

module.exports = SiteActions;
