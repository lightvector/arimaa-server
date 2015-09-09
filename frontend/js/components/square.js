var React = require('react');
var PropTypes = React.PropTypes;

var Square = React.createClass({
  propTypes: {
    black: PropTypes.bool,
    trap: PropTypes.bool,
    selected: PropTypes.bool
  },

  render: function () {
    var black = this.props.black;
    var trap = this.props.trap;
    var selected = this.props.selected;
    var stepTo = this.props.stepTo;


    var className = black ? "white" : "black";

    if(trap) {
      className += " " + "trap";
    } else if(selected) {
      className += " " + "sel";
    }
    if(stepTo) {
      className += " " + "step";
    }

    return (
      <div className={className}>
        {this.props.children}
      </div>
    );
  }
});

module.exports = Square;
