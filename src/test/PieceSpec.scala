import collection.mutable.Stack
import org.scalatest._

import org.playarimaa.util._
import org.playarimaa.board._

class PieceSpec extends FlatSpec with Matchers {

  "Piece" should "round-trip through char" in {
    Piece.values.foreach( piece =>
      Piece.ofChar(piece.toChar) should be (Ok(piece))
    )
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
