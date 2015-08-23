var ArimaaDispatcher = require('../dispatcher/ArimaaDispatcher.js');
var SiteConstants = require('../constants/SiteConstants.js');
var EventEmitter = require('events').EventEmitter;
var cookie = require('react-cookie');

var CHANGE_EVENT = 'change';

var errorText = "";

const UserStore = Object.assign({}, EventEmitter.prototype, {

  emitChange: function() {
    this.emit(CHANGE_EVENT);
  },

  addChangeListener: function(callback) {
    this.on(CHANGE_EVENT, callback);
  },

  removeChangeListener: function(callback) {
    this.removeListener(CHANGE_EVENT, callback);
  },

  //use this function later for both registration and login errors
  getLoginState: function() {
    return {error: errorText};
  },

  siteAuthToken: function() {
    return cookie.load('siteAuth');
  },

  dispatcherIndex: ArimaaDispatcher.register(function(action) {
    switch (action.actionType) {
      case SiteConstants.REGISTRATION_FAILED:
      case SiteConstants.LOGIN_FAILED:
        errorText = action.reason;
        UserStore.emitChange();
        break;
      case SiteConstants.REGISTRATION_SUCCESS:
      case SiteConstants.LOGIN_SUCCESS:
        errorText = "";
        UserStore.emitChange();
        break;
      default:
        break;
    }
    return true;
  })
});



module.exports = UserStore;
