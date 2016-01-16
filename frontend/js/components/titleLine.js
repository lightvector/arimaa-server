var React = require('react');

var titleLineComponent = React.createClass({
  render: function() {
    return (
    <h1>
      <img src={"/images/pieces/ge.png"} alt={""} className={"titlePieceLeft"}></img>
      playarimaa.org
      <img src={"/images/pieces/se.png"} alt={""} className={"titlePieceRight"}></img>
    </h1>
    );
  }
});

module.exports = titleLineComponent;

