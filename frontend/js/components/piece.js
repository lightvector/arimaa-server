var React = require('react');

function imageNameFromPieceName(p) {
  if('rcdhme'.indexOf(p) !== -1) return 's' + p;
  return 'g' + p.toLowerCase();
}

var ArimaaPiece = React.createClass({
  render: function() {
    var imageName = imageNameFromPieceName(this.props.pieceName);
    return <img src={"../images/pieces/"+imageName+".png"} alt={imageName} className={"pieceImg unselectable"}></img>;
  }
});

module.exports = ArimaaPiece;
