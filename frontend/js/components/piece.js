var React = require('react');

function imageNameFromPieceName(p) {
  if('rcdhme'.indexOf(p) !== -1) return 'b' + p;
  return 'w' + p.toLowerCase();
}

var ArimaaPiece = React.createClass({
  render: function() {
    var imageName = imageNameFromPieceName(this.props.pieceName);
    return <img src={"../images/"+imageName+".gif"} alt={imageName}></img>;
  }
});

module.exports = ArimaaPiece;
