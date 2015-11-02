var keyMirror = require('keymirror');

var SiteConstants = keyMirror({
  LOGIN_FAILED: null,
  LOGIN_SUCCESS: null,
  REGISTRATION_FAILED: null,
  REGISTRATION_SUCCESS: null,
  FORGOT_PASSWORD_FAILED: null,
  FORGOT_PASSWORD_SUCCESS: null,
  RESET_PASSWORD_FAILED: null,
  RESET_PASSWORD_SUCCESS: null,
  OPEN_GAMES_LIST: null,
  ACTIVE_GAMES_LIST: null,
  GAME_METADATA_UPDATE: null,
  GAME_REMOVED: null,
  PLAYER_JOINED: null,
  GAME_JOINED: null,
  HEARTBEAT_FAILED: null,
  LEAVE_GAME_SUCCESS: null
});

module.exports = SiteConstants;
