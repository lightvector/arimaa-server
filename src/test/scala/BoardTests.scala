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
}

/*
import org.scalatest.junit.JUnitSuite
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

import org.playarimaa.util._
import org.playarimaa.board._

class PieceTest extends JUnitSuite {

  @Test
  def testPiece() {
    Piece.values.foreach( piece =>
      assertTrue(Piece.ofChar(piece.toChar) == Ok(piece))
    )
  }
}

 */
