var React = require('react');

var ArimaaStore = require('../stores/ArimaaStore.js');
var ArimaaActions = require('../actions/ArimaaActions.js');
var ArimaaConstants = require('../constants/ArimaaConstants.js');

var Utils = require('../utils/Utils.js');

var GameClock = React.createClass({
  getInitialState: function() {
    return {
      clock: ArimaaStore.getClockRemaining(this.props.player,false),
      name: "trololol",
      rating: 1234
    };
  },

  _onChange: function() {
    this.setState({
      clock: ArimaaStore.getClockRemaining(this.props.player,false),
      name: "abc",
      rating: 1234
    });
  },

  componentDidMount: function() {
    ArimaaStore.addChangeListener(this._onChange);
    this.interval = setInterval(this._onChange,150);
  },

  componentWillUnmount: function() {
    ArimaaStore.removeChangeListener(this._onChange);
    clearInterval(this.interval);
  },

  render: function() {
    var clock = this.state.clock;
    var formatted = "-:--";
    if(clock !== null)
      formatted = Utils.timeSpanToString(Math.max(0,clock));

    if(this.props.player == "g")
      formatted = "Gold: " + formatted;
    else
      formatted = "Silver: " + formatted;

    return (
      <div>
        {formatted}
        {this.state.name}
        {this.state.rating}
      </div>
    );
  }

});

module.exports = GameClock;
