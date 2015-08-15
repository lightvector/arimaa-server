package org.playarimaa.board
import scala.util.{Try, Success, Failure}
import org.playarimaa.board.Utils._

//----------------------------------------------------------------------

/** Internal representation of a single step. */
case class Step(piece: Piece, src: Location, dir: Direction) {
  def dest: Location =
    src(dir)

  override def toString: String =
    "" + piece + src + dir
}
object Step {
  def apply(s: String): Step =
    ofString(s).get

  def ofString(s: String): Try[Step] = {
    Try {
      if(s.length != 4)
        throw new IllegalArgumentException("Token not expected length")
      else {
        val piece = Piece(s(0))
        val src = Location(s.substring(1,3))
        val dir = Direction(s(3))
        Step(piece,src,dir)
      }
    }.tagFailure("Error when parsing step '" + s + "':")
  }
}

//----------------------------------------------------------------------

/** Internal representation of a single piece placement (such as during setup). */
case class Placement(piece: Piece, dest: Location) {
  override def toString: String =
    "" + piece + dest
}
object Placement {
  def apply(s: String): Placement =
    ofString(s).get

  def ofString(s: String): Try[Placement] = {
    Try {
      if (s.length != 3)
        throw new IllegalArgumentException("Token not expected length")
      else {
        val piece = Piece(s(0))
        val dest = Location(s.substring(1,3))
        Placement(piece, dest)
      }
    }.tagFailure("Error when parsing placement '" + s + "':")
  }
}

//----------------------------------------------------------------------

/** Internal utility class representing a piece capture. */
case class Capture(piece: Piece, src: Location) {
  override def toString: String =
    "" + piece + src + "x"
}
object Capture {
  def apply(s: String): Capture =
    ofString(s).get

  def ofString(s: String): Try[Capture] = {
    Try {
      if(s.length != 4)
        throw new IllegalArgumentException("Token not expected length")
      else if(s(3) != 'x')
        throw new IllegalArgumentException("Token not does not end with 'x'")
      else {
        val piece = Piece(s(0))
        val src = Location(s.substring(1,3))
        Capture(piece,src)
      }
    }.tagFailure("Error when parsing capture '" + s + "':")
  }
}

//----------------------------------------------------------------------

/** Internal representation of a move */
sealed trait Move
case class Steps(steps: List[Step]) extends Move
case class Placements(placements: List[Placement]) extends Move

object Placements {
  def ofStringList(list: List[String]): Placements = {
    Placements(list.map(Placement(_)))
  }
}

//----------------------------------------------------------------------

/** The interface for a parser for a given notation for Arimaa moves */
trait Notation {

  /** Returns the move corresponding to [moveStr] on the current board,
    * and the resulting board position after making the move.
    */
  def readSteps(board: Board, moveStr: String): Try[(Move,Board)]
  def readPlacements(board: Board, moveStr: String, gameType: GameType): Try[(Move,Board)]

  //TODO not needed?
  /** Returns the string notation corresponding to [move] on the current board. */
  //def write(board: Board, move: Move): Try[String]

  /** Returns the string notation corresponding to the given turn */
  def turnString(plyNum: Int): String
}

//----------------------------------------------------------------------

/** Parser for standard Arimaa notation, ex: "Ec4w hc5s hc4s hc4x Eb4e" */
object StandardNotation extends Notation {

  def readPlacements(board: Board, moveStr: String, gameType: GameType): Try[(Move,Board)] = {
    def read(tokens: List[String], placements: List[Placement]): Try[Placements] = {
      tokens match {
        case Nil => Success(Placements(placements.reverse))
        case token :: tokens =>
          Placement.ofString(token).flatMap { placement =>
            read(tokens, placements :+ placement)
          }
      }
    }
    val tokens = moveStr.split("")
    read(tokens.toList,List()).tagFailure("Error parsing move '" + moveStr + "':").flatMap { placements =>
      board.setup(placements,gameType).map { board =>
        (placements,board)
      }
    }
  }

  def readSteps(board: Board, moveStr: String): Try[(Move,Board)] = {
    val player = board.player
    val opponent = player.flip

    //Read and consume tokens for the expected list of captures. Returns the remaining tokens.
    def readCaps(tokens: List[String], caps: List[Capture]): Try[List[String]] = {
      def missingCapError = Failure(new Exception("Missing token for capture " + caps(0)))
      if(caps.isEmpty)
        Success(tokens)
      else {
        tokens match {
          case Nil => missingCapError
          case token :: tokens =>
            Capture.ofString(token) match {
              case Failure(_) => missingCapError
              case Success(cap) =>
                if(!caps.contains(cap))
                  Failure(new Exception("Unexpected capture token: " + cap))
                else
                  readCaps(tokens, caps.filter(_ != cap))
            }
        }
      }
    }

    //Read and consume tokens for the steps of a move.
    def readStep(
      board: Board,
      tokens: List[String],
      steps: List[Step],
      //Some(pt,loc): A player piece stronger than [pt] must step into [loc] next
      pushLocAndPower: Option[(PieceType, Location)],
      //Some(pt,loc): A opponent piece weaker than [pt] may step into [loc] next
      pullLocAndPower: Option[(PieceType, Location)]
    ): Try[(Move,Board)] = {
      tokens match {
        //End of move
        case Nil =>
          if(pushLocAndPower.nonEmpty) Failure(new Exception("Incomplete push at end of move"))
          else                         Success(Steps(steps.reverse),board.endTurn)
        //Normal case
        case token :: tokens =>
          Step.ofString(token).flatMap { step =>
            board.step(step).flatMap { board =>
              //Check push condition
              if(pushLocAndPower.exists { case (pt,loc) => !(step.dest == loc && step.piece.owner == player && step.piece.pieceType > pt) })
                Failure(new Exception("Illegal push at " + pushLocAndPower.get._2))

              //Require the next step to be a push if we moved an opponent piece, unless it can be counted as a pull
              val newPushLocAndPower = {
                if(step.piece.owner == opponent && !pullLocAndPower.exists { case (pt,loc) => step.dest == loc && step.piece.pieceType < pt })
                  Some(step.piece.pieceType, step.src)
                else
                  None
              }

              //Allow the next step to be a pull of an opponent's piece into the square we moved out of, unless we just pushed
              val newPullLocAndPower = {
                if(step.piece.owner == player && pushLocAndPower == None)
                  Some(step.piece.pieceType, step.src)
                else
                  None
              }

              //Resolve captures and ensure that we have capture tokens
              val (newBoard,caps) = board.resolveCaps
              readCaps(tokens,caps).flatMap { tokens =>
                //And loop!
                readStep(newBoard, tokens, steps :+ step, newPushLocAndPower, newPullLocAndPower)
              }
            }
          }
      }
    }

    val tokens = moveStr.split("")
    readStep(board,tokens.toList,List(),None,None).tagFailure("Error parsing move '" + moveStr + "':")
  }


  def turnString(plyNum: Int): String = {
    if(plyNum % 2 == 0)
      (1 + plyNum / 2) + "g"
    else
      (1 + plyNum / 2) + "s"
  }
}
