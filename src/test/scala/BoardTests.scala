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

  "Piece" should "decode characters correctly" in {
    Piece.ofChar('R') should be (Success(Piece(GOLD, RAB)))
    Piece.ofChar('C') should be (Success(Piece(GOLD, CAT)))
    Piece.ofChar('D') should be (Success(Piece(GOLD, DOG)))
    Piece.ofChar('H') should be (Success(Piece(GOLD, HOR)))
    Piece.ofChar('M') should be (Success(Piece(GOLD, CAM)))
    Piece.ofChar('E') should be (Success(Piece(GOLD, ELE)))

    Piece.ofChar('r') should be (Success(Piece(SILV, RAB)))
    Piece.ofChar('c') should be (Success(Piece(SILV, CAT)))
    Piece.ofChar('d') should be (Success(Piece(SILV, DOG)))
    Piece.ofChar('h') should be (Success(Piece(SILV, HOR)))
    Piece.ofChar('m') should be (Success(Piece(SILV, CAM)))
    Piece.ofChar('e') should be (Success(Piece(SILV, ELE)))
  }

  "Piece" should "have correct owner" in {
    Piece(GOLD, RAB).owner should be (GOLD)
    Piece(SILV, RAB).owner should be (SILV)
  }

  "Piece" should "have correct piece type" in {
    Piece(GOLD, RAB).pieceType should be (RAB)
    Piece(SILV, CAM).pieceType should be (CAM)
  }

  "PieceType" should "decode characters correctly" in {
    PieceType.ofChar('r') should be (Success(RAB))
    PieceType.ofChar('c') should be (Success(CAT))
    PieceType.ofChar('d') should be (Success(DOG))
    PieceType.ofChar('h') should be (Success(HOR))
    PieceType.ofChar('m') should be (Success(CAM))
    PieceType.ofChar('e') should be (Success(ELE))

    PieceType.ofChar('R') should be (Success(RAB))
    PieceType.ofChar('C') should be (Success(CAT))
    PieceType.ofChar('D') should be (Success(DOG))
    PieceType.ofChar('H') should be (Success(HOR))
    PieceType.ofChar('M') should be (Success(CAM))
    PieceType.ofChar('E') should be (Success(ELE))
  }

  "PieceType" should "return failure for invalid character" in {
    PieceType.ofChar('w').isFailure should be (true)
  }

  "PieceType" should "have correct relative strengths" in {
    ELE.compare(CAM) should be (1)
    CAM.compare(HOR) should be (1)
    HOR.compare(DOG) should be (1)
    DOG.compare(CAT) should be (1)
    CAT.compare(RAB) should be (1)

    CAM.compare(ELE) should be (-1)
    HOR.compare(CAM) should be (-1)
    DOG.compare(HOR) should be (-1)
    CAT.compare(DOG) should be (-1)
    RAB.compare(CAT) should be (-1)

    PieceType.values.foreach( piece =>
      piece.compare(piece) should be (0)
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
