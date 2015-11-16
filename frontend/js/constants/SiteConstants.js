var keyMirror = require('keymirror');

var ACTIONS = keyMirror({
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

const VALUES = {
  //Seconds between queries for refreshing open or active games lists in the gameroom
  GAME_LIST_LOOP_DELAY: 6.0,
  GAME_LIST_LOOP_DELAY_ON_ERROR: 30.0,
  //Seconds to wait before another polling query for an open game that we've joined
  JOINED_GAME_META_LOOP_DELAY: 0.2,
  JOINED_GAME_META_LOOP_DELAY_ON_ERROR: 2.0,
  //Seconds between heartbeats of an open game or active game that we've joined
  GAME_HEARTBEAT_PERIOD: 5.0,
  //Seconds to wait before another polling query for a game state for a game we're playing
  GAME_STATE_LOOP_DELAY: 0.2,
  GAME_STATE_LOOP_DELAY_ON_ERROR: 2.0
};


module.exports = {
  ACTIONS: ACTIONS,
  VALUES: VALUES
};
