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

    var className = "subSquare unselectable";
    if(selected) {
      className += " " + "selected";
    }
    if(stepTo) {
      className += " " + "stepTo";
    }

    var showSquareNames = false; //TODO make this controlled by something?
    var displayedName = showSquareNames ? sqName : "";
    
    return (
      <div className={className}>
        {this.props.children}
        <span className={"coordLabel unselectable"}> {displayedName} </span>
      </div>
    );
  }
});

module.exports = Square;
