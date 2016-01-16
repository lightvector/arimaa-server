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
  VERIFY_EMAIL_FAILED: null,
  VERIFY_EMAIL_SUCCESS: null,
  RESEND_VERIFY_EMAIL_FAILED: null,
  RESEND_VERIFY_EMAIL_SUCCESS: null,
  USERS_LOGGED_IN_LIST: null,
  NOTIFICATIONS_LIST: null,
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
  //TODO if we could get atmosphere working, replacing all these loops with a simple event stream
  //would be really nice
  
  //Seconds between queries for refreshing open or active games lists in the gameroom
  GAME_LIST_LOOP_DELAY: 10.0,
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
  //This also serves as a heartbeat for the site so it won't log us out
  LOGIN_CHECK_LOOP_DELAY: 20.0,
  //Poll rate for acquiring any notifications for the user
  NOTIFICATIONS_LOOP_DELAY: 60.0,

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


const SETTINGS = {
  //Movement controls for board gui
  MOVEMENT_MODE_KEY: "setting_movement_mode",
  MOVEMENT_MODE: {
    //Click piece to select, click destination
    CLICKCLICK: "clickclick",
    //Hover piece to select, click destination
    HOVERCLICK: "hoverclick",
    DEFAULT: "hoverclick"
  }
};

module.exports = {
  ACTIONS: ACTIONS,
  VALUES: VALUES,
  SETTINGS: SETTINGS
};
