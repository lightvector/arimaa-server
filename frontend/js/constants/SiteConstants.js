var keyMirror = require('keymirror');

var ACTIONS = keyMirror({
  LOGIN_FAILED: null,
  LOGIN_SUCCESS: null,
  LOGOUT_FAILED: null,
  REGISTRATION_FAILED: null,
  REGISTRATION_SUCCESS: null,
  FORGOT_PASSWORD_FAILED: null,
  FORGOT_PASSWORD_SUCCESS: null,
  RESET_PASSWORD_FAILED: null,
  RESET_PASSWORD_SUCCESS: null,
  USERS_LOGGED_IN_LIST: null,
  OPEN_GAMES_LIST: null,
  ACTIVE_GAMES_LIST: null,
  GAMEROOM_UPDATE_FAILED: null,
  GAME_METADATA_UPDATE: null,
  GAME_REMOVED: null,
  CREATE_GAME_FAILED: null,
  GAME_JOINED: null,
  HEARTBEAT_FAILED: null,
  LEAVING_GAME: null,
  LEAVE_GAME_SUCCESS: null
});

const VALUES = {
  //Seconds between queries for refreshing open or active games lists in the gameroom
  GAME_LIST_LOOP_DELAY: 6.0,
  GAME_LIST_LOOP_DELAY_ON_ERROR: 30.0,
  //Seconds to wait before another polling query for an open game that we've joined
  JOINED_GAME_META_LOOP_DELAY: 0.2,
  JOINED_GAME_META_LOOP_DELAY_ON_ERROR: 5.0,
  //Seconds between heartbeats of an open game or active game that we've joined
  GAME_HEARTBEAT_PERIOD: 5.0,
  //Seconds to wait before another polling query for a game state for a game we're playing
  GAME_STATE_LOOP_DELAY: 0.2,
  GAME_STATE_LOOP_DELAY_ON_ERROR: 5.0,

  //Check if we need to go to login page if not logged in this often
  LOGIN_CHECK_LOOP_DELAY: 20.0,
  
  //Seconds to wait between heartbeats for chatroom
  CHAT_HEARTBEAT_PERIOD: 30.0,
  //Max chat lines to keep in history
  CHAT_MAX_HISTORY_LINES: 10000,
  //Seconds to wait between another polling query for chat lines
  CHAT_LOOP_DELAY: 0.3,
  CHAT_LOOP_DELAY_ON_ERROR: 5.0,

  //Seconds to add a class to trigger highlight css animation
  HIGHLIGHT_FLASH_TIMEOUT: 0.2
};


module.exports = {
  ACTIONS: ACTIONS,
  VALUES: VALUES
};
