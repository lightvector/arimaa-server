var keyMirror = require('keymirror');

var SiteConstants = keyMirror({
  LOGIN_FAILED: null,
  LOGIN_SUCCESS: null,
  REGISTRATION_FAILED: null,
  REGISTRATION_SUCCESS: null,
  GAME_CREATED: null, 
  PLAYER_JOINED: null,
  GAME_STATUS_UPDATE: null
});

module.exports = SiteConstants;
