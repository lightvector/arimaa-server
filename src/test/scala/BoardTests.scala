import collection.mutable.Stack
import scala.util.{Try, Success, Failure}
import org.scalatest._

import org.playarimaa.board._

class BoardTests extends FlatSpec with Matchers {

  "Piece" should "round-trip through char" in {
    Piece.values.foreach( piece =>
      Piece(piece.toChar) should be (piece)
    )
  }

  it should "parse valid characters correctly" in {
    Piece('R') should be (Piece(GOLD, RAB))
    Piece('C') should be (Piece(GOLD, CAT))
    Piece('D') should be (Piece(GOLD, DOG))
    Piece('H') should be (Piece(GOLD, HOR))
    Piece('M') should be (Piece(GOLD, CAM))
    Piece('E') should be (Piece(GOLD, ELE))

    Piece('r') should be (Piece(SILV, RAB))
    Piece('c') should be (Piece(SILV, CAT))
    Piece('d') should be (Piece(SILV, DOG))
    Piece('h') should be (Piece(SILV, HOR))
    Piece('m') should be (Piece(SILV, CAM))
    Piece('e') should be (Piece(SILV, ELE))
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

  it should "detect freezing correctly" in {
    // Same owner
    GOLD_RAB.canFreeze(GOLD_RAB) should be (false)
    GOLD_CAT.canFreeze(GOLD_RAB) should be (false)
    GOLD_RAB.canFreeze(GOLD_CAT) should be (false)
    SILV_RAB.canFreeze(SILV_RAB) should be (false)
    SILV_CAT.canFreeze(SILV_RAB) should be (false)
    SILV_RAB.canFreeze(SILV_CAT) should be (false)

    // Different owners
    GOLD_RAB.canFreeze(SILV_RAB) should be (false)
    GOLD_CAT.canFreeze(SILV_RAB) should be (true)
    GOLD_RAB.canFreeze(SILV_CAT) should be (false)
    SILV_RAB.canFreeze(GOLD_RAB) should be (false)
    SILV_CAT.canFreeze(GOLD_RAB) should be (true)
    SILV_RAB.canFreeze(GOLD_CAT) should be (false)
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
    Location("a1") should be (Location(0, 0))
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
    new Board().add(GOLD_RAB, Location("a8")).get
        .toStringAei should be ("[R                                                               ]")
    new Board().add(SILV_RAB, Location("a8")).get
        .add(GOLD_HOR, Location("b1")).get
        .toStringAei should be ("[r                                                        H      ]")
  }

  it should "step pieces to empty squares correctly. (no captures, no errors)" in {
    var board = new Board().add(GOLD_RAB, Location.ofString("a8").get).get
    board.toStringAei should be("[R                                                               ]")
    val step = new Step(GOLD_RAB, Location.ofString("a8").get, EAST)
    board = board.step(step).get
    board.toStringAei should be("[ R                                                              ]")
    val step2 = new Step(GOLD_RAB, Location.ofString("b8").get, SOUTH)
    board = board.step(step2).get
    board.toStringAei should be("[         R                                                      ]")
  }

  it should "give an error if you try to step a piece off the board" in {
    var board = new Board().add(GOLD_RAB, Location.ofString("a8").get).get
    board.toStringAei should be("[R                                                               ]")
    val step = new Step(GOLD_RAB, Location.ofString("a8").get, WEST)
    board.step(step).isFailure should be (true)
  }

  it should "give an error if you try to step a piece onto another piece" in {
    var board = new Board().add(GOLD_RAB, Location.ofString("a8").get).get
    board = board.add(GOLD_RAB, Location.ofString("b8").get).get
    board.toStringAei should be("[RR                                                              ]")
    val step = new Step(GOLD_RAB, Location.ofString("a8").get, EAST)
    board.step(step).isFailure should be (true)
  }

  "Location" should "detect traps correctly" in {
    Location("c3").isTrap should be (true)
    Location("c6").isTrap should be (true)
    Location("f3").isTrap should be (true)
    Location("f6").isTrap should be (true)

    Location("a1").isTrap should be (false)
    Location("a8").isTrap should be (false)
    Location("h1").isTrap should be (false)
    Location("h8").isTrap should be (false)
    Location("c2").isTrap should be (false)
    Location("c4").isTrap should be (false)
    Location("b3").isTrap should be (false)
    Location("d3").isTrap should be (false)
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
