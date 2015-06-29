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

  it should "parse valid characters correctly" in {
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

  it should "parse invalid characters correctly" in {
    Piece.ofChar('w').isFailure should be (true)
  }

  it should "have correct owner" in {
    Piece(GOLD, RAB).owner should be (GOLD)
    Piece(SILV, RAB).owner should be (SILV)
  }

  it should "have correct piece type" in {
    Piece(GOLD, RAB).pieceType should be (RAB)
    Piece(SILV, CAM).pieceType should be (CAM)
  }

  "PieceType" should "parse valid characters correctly" in {
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

  it should "parse invalid characters correctly" in {
    PieceType.ofChar('w').isFailure should be (true)
  }

  it should "have correct relative strengths" in {
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

  "Location" should "parse valid strings correctly" in {
    Location.ofString("a1") should be (Success(new Location(0, 0)))
  }

  it should "parse invalid strings correctly" in {
    Location.ofString("a0").isFailure should be (true)
    Location.ofString("a9").isFailure should be (true)
    Location.ofString("i0").isFailure should be (true)
    Location.ofString("i9").isFailure should be (true)
  }

  it should "print toString correctly" in {
    Location(0, 0).toString should be ("a1")
    Location(0, 7).toString should be ("a8")
    Location(7, 0).toString should be ("h1")
    Location(7, 7).toString should be ("h8")
  }

  /*
   Location.ofString and Location.toString and the notation parsing built on it (steps, moves)
   currently require Board.SIZE <= 9 for the "a1"/"h8"-like notation.
   If for some reason you want to make the board size larger, please update the notation-related
   code and any failing tests.
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

  it should "detect out of bounds correctly" in {
    Board.isOutOfBounds(Location(0,0)) should be (false)
    Board.isOutOfBounds(Location(0,7)) should be (false)
    Board.isOutOfBounds(Location(7,0)) should be (false)
    Board.isOutOfBounds(Location(7,7)) should be (false)

    Board.isOutOfBounds(Location(-1,0)) should be (true)
    Board.isOutOfBounds(Location(0,-1)) should be (true)
    Board.isOutOfBounds(Location(8,0)) should be (true)
    Board.isOutOfBounds(Location(0,8)) should be (true)

  }
}
