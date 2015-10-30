var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');
var Utils = require('../utils/Utils.js');

var CreateGameDialog = React.createClass({
  getInitialState: function() {
    return {tcIdx:2, sideToPlay: "Random", rated: true, gameType:"standard"};
  },

  //Time controls to choose between
  timeControls: [
    {label:"Lightning", tc:{initialTime:360, increment:5, overtimeAfter:80}},
    {label:"Blitz", tc:{initialTime:480, increment:10, overtimeAfter:80}},
    {label:"Fast",  tc:{initialTime:960, increment:20, overtimeAfter:80}},
    {label:"Normal", tc:{initialTime:1800, increment:30, overtimeAfter:80}},
    {label:"Slow", tc:{initialTime:2700, increment:45, overtimeAfter:80}},
    {label:"Blitz (delay)", tc:{initialTime:480, delay:15, overtimeAfter:80}},
    {label:"Fast (delay)",  tc:{initialTime:960, delay:30, overtimeAfter:80}},
  ],

  handleTCChange: function(event) {
    this.setState({tcIdx: event.target.selectionIndex});
  },
  handleSideToPlayChange: function(event) {
    this.setState({sideToPlay: event.target.value});
  },
  handleRatedChange: function(event) {
    this.setState({rated: event.target.value});
  },
  handleSubmit: function(){
    var props;
    if(sideToPlay === "Gold")
      props = {
        tc: timeControls[this.state.tcIdx].tc,
        gUser: UserStore.getUsername(),
        rated: this.state.rated,
        gameType: "standard",
        siteAuth: UserStore.siteAuthToken()
      };
    else if(sideToPlayer === "Silver")
      props = {
        tc: timeControls[this.state.tcIdx].tc,
        sUser: UserStore.getUsername(),
        rated: this.state.rated,
        gameType: "standard",
        siteAuth: UserStore.siteAuthToken()
      };
    else
      props = {
        tc: timeControls[this.state.tcIdx].tc,
        rated: this.state.rated,
        gameType: "standard",
        siteAuth: UserStore.siteAuthToken()
      };
    this.props.handleSubmitted(props);
  },
  render: function(){
    var timeControls = this.timeControls;
    var selections = [];
    for(var i = 0; i<timeControls.length; i++) {
      var selection = React.createElement("option", {key: "tcOption_"+i, value: timeControls[i].label + " " + Utils.gameTCString(timeControls[i].tc)});
      selections.push(selection);
    }
    var tcInput = React.createElement("select", {key: "tcSelect", size: timeControls.length, onChange: this.handleTCChange, value: selections[this.state.tcIdx].value}, selections    );

    return(
      <div>
      <form onsubmit={function() {return false;}}>
      <h4>Time control:</h4>
      {tcInput}
      <h4>Side to play:</h4>
      <select size='3' onChange={this.handleSideToPlayChange} value="Random">
        <option value="Random"/>
        <option value="Gold"/>
        <option value="Silver"/>
      </select>
      <input type="checkbox" checked={this.state.rated} onChange={this.handleRatedChange}>Rated</input>
      <button onclick={this.handleSubmit}>Ok</button>
      </form>
      </div>
    );
  }
});

module.exports = CreateGameDialog;
