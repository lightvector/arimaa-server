var React = require('react');
var SiteActions = require('../actions/SiteActions.js');
var UserStore = require('../stores/UserStore.js');

var component = React.createClass({
  componentDidMount: function() {
     UserStore.addChangeListener(this._onChange);
  },
  componentWillUnmount: function() {
     UserStore.removeChangeListener(this._onChange);
  },

  _onChange: function(e) {
    e.preventDefault();
    console.log('o');
    //this.setState(UserStore.getLoginState());
  },

  createGame: function() {
    var sUser = this.refs.silverUser.getDOMNode().value;
    var gUser = this.refs.goldUser.getDOMNode().value;
    var gameType = this.refs.gameType.getDOMNode().value;
    SiteActions.createGame(gameType);
  },

  joinGame: function() {
    var gId = this.refs.gameId.getDOMNode().value;
    SiteActions.joinGame(gId);
  },

  logout: function() {
    SiteActions.logout();
  },

  render: function() {
    return (
      <div>
       <h1>DEBUG</h1>
       <button type="button" onClick={this.logout}>Logout</button>

       <form onSubmit={this._onChange}>
         <input type="text" ref="silverUser" placeholder="goldUser"/>
         <input type="text" ref="goldUser" placeholder="silverUser"/>
         <input type="text" ref="gameType" placeholder="standard,handicap,directsetup" defaultValue="standard"/>
         <input type="submit" className="submit" name="commit" value="Create Game" onClick={this.createGame}/>
       </form>

       <form onSubmit={this._onChange}>
         <input type="text" ref="gameId" placeholder="gameId"/>
         <input type="submit" className="submit" name="commit" value="Join Game" onClick={this.joinGame}/>
       </form>

      </div>
    );
  }


});

module.exports = component;
