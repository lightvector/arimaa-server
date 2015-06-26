import collection.mutable.Stack
import scala.util.{Try, Success, Failure}
import org.scalatest._

import org.playarimaa.board._

class BoardTests extends FlatSpec with Matchers {

  "Piece" should "round-trip through char" in {
    Piece.values.foreach( piece =>
      Piece.ofChar(piece.toChar) should be (Success(piece))
    )
  }

  /*
   Location.ofString and Location.toString and the notation parsing built on it (steps, moves)
   currently require Board.SIZE <= 9 for the "a1"/"h8"-like notation.
   If for some reason you want to make the board size larger, please update the notation-related
   code and this test.
   */
  "Board" should "not be larger than size 9 due to notation" in {
    assert(Board.SIZE <= 9)
  }

  "Location" should "detect traps correctly" in {
    Location.ofString("c3").map(x => x.isTrap).get should be (true)
    Location.ofString("c6").map(x => x.isTrap).get should be (true)
    Location.ofString("f3").map(x => x.isTrap).get should be (true)
    Location.ofString("f6").map(x => x.isTrap).get should be (true)

    Location.ofString("a1").map(x => x.isTrap).get should be (false)
    Location.ofString("a8").map(x => x.isTrap).get should be (false)
    Location.ofString("h1").map(x => x.isTrap).get should be (false)
    Location.ofString("h8").map(x => x.isTrap).get should be (false)
    Location.ofString("c2").map(x => x.isTrap).get should be (false)
    Location.ofString("c4").map(x => x.isTrap).get should be (false)
    Location.ofString("b3").map(x => x.isTrap).get should be (false)
    Location.ofString("d3").map(x => x.isTrap).get should be (false)
  }
}
