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

  it should "print toStringAei correctly" in {
    new Board().toStringAei should be ("[                                                                ]")
    new Board().add(new Piece(GOLD, RAB), Location.ofString("a8").get).get
        .toStringAei should be ("[R                                                               ]")
    new Board().add(new Piece(SILV, RAB), Location.ofString("a8").get).get
        .add(new Piece(GOLD, HOR), Location.ofString("b1").get).get
        .toStringAei should be ("[r                                                        H      ]")
  }
}
