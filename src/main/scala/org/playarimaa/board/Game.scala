package org.playarimaa.board
import scala.util.{Try, Success, Failure}
import org.playarimaa.board.Utils._

object Game {
  sealed trait EndingReason
  case object GOAL extends EndingReason
  case object ELIMINATION extends EndingReason
  case object IMMOBILIZATION extends EndingReason
}

class Game private (
  val boards: Vector[Board],
  val moves: Vector[Move],
  val gameType: GameType
){
  def this(gt: GameType) =
    this(Vector(new Board()), Vector(), gt)

  def parseAndMakeMove(moveStr: String, notation: Notation): Try[Game] = {
    val result = {
      //Setup vs regular move
      if(moves.length >= 2) notation.readSteps(boards.last, moveStr)
      else                  notation.readPlacements(boards.last, moveStr, gameType)
    }

    result.flatMap { case (move,board) =>
      if(boards.last.samePositionAs(board))
        Failure(new IllegalArgumentException("Illegal move (unchanged position): " + moveStr))
      else if(situationOccursTwice(board))
        Failure(new IllegalArgumentException("Illegal move (3x repetition): " + moveStr))
      else
        Success(new Game(boards :+ board, moves :+ move, gameType))
    }
  }
  private def isLegalBoardAfterNextMove(board: Board): Boolean = {
    if(boards.last.samePositionAs(board))
      false
    else if(situationOccursTwice(board))
      false
    else
      true
  }


  /** Returns true if [board] occurs at least twice in this game's history */
  def situationOccursTwice(board: Board): Boolean = {
    //TODO a bit inefficient?
    //Exclude the first 2 boards since they're during setup
    boards.zipWithIndex.count { case (b,idx) => idx >= 2 && b.sameSituationAs(board) } >= 2
  }

  private def isGoalFor(board: Board, p: Player): Boolean = {
    board.pieces.exists {
      case (loc,piece) => piece.owner == p && piece.pieceType == RAB && Board.isGoal(p,loc)
    }
  }

  private def isEliminated(board: Board, p: Player): Boolean = {
    !board.pieces.exists {
      case (_,piece) => piece.owner == p && piece.pieceType == RAB
    }
  }

  private def hasLegalMove(board: Board, p: Player): Boolean = {
    //True if the the board as-is would be a legal move
    if(board.stepsLeft < Board.STEPS_PER_TURN && isLegalBoardAfterNextMove(board.endTurn))
      true
    else if(board.stepsLeft <= 0)
      false
    else {
      //Check if there exists a piece that can a make a legal move
      board.pieces.exists { case (src,piece) =>
        //Piece must be owned by us and unfrozen and have a direction it can move
        piece.owner == p && !board.isFrozen(src) && Direction.values.exists { case dir: Direction =>
          //Direction must be okay if it's a rabbit
          (piece.pieceType != RAB || Board.canRabbitGoInDir(p,dir)) && {
            val dest = src(dir)
            //Branch based on contents of destination to see if we can move there.
            //In each case, play the move and recurse to see if we can obtain a full legal move
            board(dest) match {
              case OffBoard => false
              case Empty =>
                //Regular step
                hasLegalMove(board.stepAndResolveNoCheck(src,dir), p) ||
                //Pull
                Direction.values.exists { case pullFromDir: Direction =>
                  val pullSrc = src(pullFromDir)
                  //Pull is possible if there is a pullee...
                  board(pullSrc) match {
                    case OffBoard | Empty => false
                    case HasPiece(pullee) =>
                      //And the pullee is an opponent piece weaker than us
                      pullee.owner == p.flip && pullee.pieceType < piece.pieceType &&
                      hasLegalMove(board.stepAndResolveNoCheck(src,dir).stepAndResolveNoCheck(pullSrc,pullFromDir.flip), p)
                  }
                }
              //Push - push is possible if there is a blocker
              case HasPiece(blocker) =>
                //And the blocker is an opponent piece weaker than us,
                blocker.owner == p.flip && blocker.pieceType < piece.pieceType &&
                //And there is an empty space to push it to
                Direction.values.exists { pushDir =>
                  val pushDest = dest(pushDir)
                  board(pushDest) match {
                    case OffBoard | HasPiece(_) => false
                    case Empty =>
                      hasLegalMove(board.stepAndResolveNoCheck(dest,pushDir).stepAndResolveNoCheck(src,dir), p)
                  }
                }
            }
          }
        }
      }
    }
  }


  /** Returns the winner of the game based on the current move history, or None if nobody has won */
  def winner: Option[(Player,Game.EndingReason)] = {
    //TODO a bit inefficient?
    val board = boards.last
    val player = board.player
    val opponent = player.flip

    //Nobody can win based on position state during setup
    if(boards.length <= 2)
      None
    else if(isGoalFor(board,opponent))
      Some((opponent,Game.GOAL))
    else if(isGoalFor(board,player))
      Some((player,Game.GOAL))
    else if(isEliminated(board,player))
      Some((opponent,Game.ELIMINATION))
    else if(isEliminated(board,opponent))
      Some((player,Game.ELIMINATION))
    else if(!hasLegalMove(board,player))
      Some((opponent,Game.IMMOBILIZATION))
    else
      None
  }


  /* Standard format for returning in server queries and/or caching in database */
  def currentBoardString: String = {
    boards.last.toStandardString
  }

}
