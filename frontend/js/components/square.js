var React = require('react');
var PropTypes = React.PropTypes;

var Square = React.createClass({
  propTypes: {
    selected: PropTypes.bool,
    sqName: PropTypes.string
  },

  render: function () {
    var selected = this.props.selected;
    var stepTo = this.props.stepTo;
    var sqName = this.props.sqName;

    var className = "subSquare";
    if(selected) {
      className += " " + "selected";
    }
    if(stepTo) {
      className += " " + "stepTo";
    }

    return (
      <div className={className}>
        {this.props.children}
        <span className={"coordLabel"}> {sqName} </span>
      </div>
    );
  }
});

module.exports = Square;
