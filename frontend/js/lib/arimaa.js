var Arimaa = function(options) {
  //TODO share these with ArimaaConstants
  //Constants
  const NULL_COLOR = -1;
  const GOLD = 0;
  const SILVER = 1;

  const SQUARES = {
    a8:   0, b8:   1, c8:   2, d8:   3, e8:   4, f8:   5, g8:   6, h8:   7, //0x00 0x07
    a7:  16, b7:  17, c7:  18, d7:  19, e7:  20, f7:  21, g7:  22, h7:  23, //0x10 0x17
    a6:  32, b6:  33, c6:  34, d6:  35, e6:  36, f6:  37, g6:  38, h6:  39, //0x20 0x27
    a5:  48, b5:  49, c5:  50, d5:  51, e5:  52, f5:  53, g5:  54, h5:  55, //0x30
    a4:  64, b4:  65, c4:  66, d4:  67, e4:  68, f4:  69, g4:  70, h4:  71, //0x40
    a3:  80, b3:  81, c3:  82, d3:  83, e3:  84, f3:  85, g3:  86, h3:  87, //0x50
    a2:  96, b2:  97, c2:  98, d2:  99, e2: 100, f2: 101, g2: 102, h2: 103, //0x60
    a1: 112, b1: 113, c1: 114, d1: 115, e1: 116, f1: 117, g1: 118, h1: 119  //0x70
  };

  const TRAPS = {
    c3: 82,
    c6: 34,
    f3: 85,
    f6: 37
  };

  const EMPTY = 0;
  const GRABBIT = 1;
  const GCAT = 2;
  const GDOG = 3;
  const GHORSE = 4;
  const GCAMEL = 5;
  const GELEPHANT = 6;
  const COLOR = 8;
  const SRABBIT = 9;
  const SCAT = 10;
  const SDOG = 11;
  const SHORSE = 12;
  const SCAMEL = 13;
  const SELEPHANT = 14;
  const COUNT = 15;
  const PIECES = " RCDHME  rcdhme";

  const DIRECTIONS = {
    NORTH:-16,
    EAST:1,
    WEST:-1,
    SOUTH:16,
    n:-16,
    e:1,
    w:-1,
    s:16,
    '-16':'n',
    '1':'e',
    '-1':'w',
    '16':'s'
  };

  var DECOLOR = ~0x8;

  const Piece = {
    EMPTY: 0,
    GRABBIT: 1,
    GCAT: 2,
    GDOG: 3,
    GHORSE: 4,
    GCAMEL: 5,
    GELEPHANT: 6,
    COLOR: 8,
    SRABBIT: 9,
    SCAT: 10,
    SDOG: 11,
    SHORSE: 12,
    SCAMEL: 13,
    SELEPHANT: 14,
    COUNT: 15,
    PCHARS: " RCDHMExxrcdhme",
    DECOLOR: ~0x8 // ~COLOR
  };

  var options = options || {
    fen: "8/8/8/8/8/8/8/8",
    halfmoveNumber:0,
    colorToMove:GOLD,
    stepsLeft:4,
    ongoingMove:[],
    boardHistory:[],
    moveHistory:[]
  };

  var fen = options['fen'] || "8/8/8/8/8/8/8/8"; //only used to set position, use get_fen for fen at some point in time
  var board = new Array(128);
  for(var i=0;i<128;i++) {board[i] = 0;}

  parse_fen(fen);
  //is it really neccessary to have board history be an option?
  //maybe just have movehistory and calculate boardhistory by playing through the game??
  var boardHistory = options['boardHistory'] || [fen]; //nb empty array is truthy
  var moveHistory = options['moveHistory'] || [];
  var ongoingMove = options['ongoingMove'] || [];
  var halfmoveNumber = options['halfmoveNumber'] || 0;
  var colorToMove = options['colorToMove'] || 0; //GOLD == 0 is falsey

  //need to do this since 0 is falsey
  //though we probably never need to consider starting a position
  //where the player has 0 steps left

  //maybe we should just calculate this using the 4-ongoingMove.length
  var stepsLeft = 4;
  if(options['stepsLeft'] != undefined) {
    stepsLeft = options['stepsLeft'];
  }

  var stepStack = []; //used for undoing/redoing steps

  //make this public? don't know how useful
  function square_name(sqNum) {
    var x = (8-Math.floor(sqNum / 16)).toString();
    var y = sqNum % 8;
    return "abcdefgh".charAt(y) + x;
  }

  //more information than you ever will want
  //make this more object oriented-y later and add better special options support
  var ArimaaStep = function(piece, fromSqNumber, direction, specialOptions) {
    return {
      piece : piece,
      squareNum : fromSqNumber,
      square : square_name(fromSqNumber),
      destSquareNum : fromSqNumber + DIRECTIONS[direction],
      destSquare : square_name(fromSqNumber + DIRECTIONS[direction]),
      direction : direction,
      special : specialOptions, //do something else later... //TODO unused??
      string : PIECES.charAt(piece)+square_name(fromSqNumber)+direction
    };
  };

  function get_prev_step_no_caps() {
    var prevStep = null;
    if(ongoingMove.length > 0) {
      prevStep = ongoingMove[ongoingMove.length-1];

      if(prevStep.direction === 'x') {
        prevStep = null;
        if(ongoingMove.length > 1)
          prevStep = ongoingMove[ongoingMove.length-2];
      }
    }
    return prevStep;
  }

  function can_undo_step() {
    return ongoingMove.length > 0;
  }

  function can_redo_step() {
    return stepStack.length > 0;
  }

  //need to undo 2 steps if one is a capture
  function undo_step() {
    if(ongoingMove.length === 0) return null;
    var prevStep = ongoingMove.pop();
    if(prevStep['direction'] === 'x') {
      place_piece(prevStep['piece'], prevStep['squareNum']);
      if(ongoingMove.length === 0) return null; //this shouldn't ever happen in a normal game
      prevStep = ongoingMove.pop();
    }

    stepStack.push(prevStep); //we don't need to push the capture step since that will be automatically added once we redo a step that could lead to capture
    stepsLeft += 1;
    var currentSquare = prevStep['squareNum'] + DIRECTIONS[prevStep['direction']];
    remove_piece_from_square(currentSquare); //
    place_piece(prevStep['piece'], prevStep['squareNum']);
    return prevStep;
  }

  function redo_step() {
    if(stepStack.length === 0) return null;
    return add_step(stepStack[stepStack.length-1].string).step; //we actually pop the step in the add_step function
  }

  function undo_ongoing_move() {
    while(ongoingMove.length > 0) {
      undo_step();
    }
  }

  function redo_ongoing_move() {
    while(stepStack.length > 0) {
      redo_step();
    }
  }

  //returns true if move can be completed
  //reasons for false:position same, 3x repetition
  function complete_move() {
    var canComplete = can_complete_move();
    if(!canComplete.success) return canComplete;

    var currentFen = generate_fen();
    boardHistory.push(currentFen);
    colorToMove = (colorToMove === GOLD) ? SILVER : GOLD;
    stepsLeft = 4;
    moveHistory.push(ongoingMove);
    ongoingMove = [];
    halfmoveNumber += 1;

    return {success: true};
  }

  function can_complete_move() {
    var currentFen = generate_fen();

    //no move //we should add the starting position to move history and remove this check later
    //ive added the starting position, remove the following line later and TEST
    if(ongoingMove.length === 0) return {success: false, reason: "No steps taken"};

    if(boardHistory.length) {
      if(currentFen === boardHistory[boardHistory.length-1])
        return {success: false, reason: "Position hasn't changed"};
    }

    if(has_uncompleted_push())
      return {success: false, reason: "Incomplete push"};

    var count = 0;
    for(var i=0;i<boardHistory.length;i++) {
      if(currentFen == boardHistory[i]) count += 1;
      if(count == 2) return {success: false, reason: "Three times repetition"};
    }
    return {success:true};
  }

  function can_setup() {
    if(halfmoveNumber >= 2)
      return {success: false, reason: "Setup not possible after the first move"};
    return {success:true};
  }

  //NO ERROR CHECKING!!!
  function setup(setupString) {
    var canComplete = can_setup();
    if(!canComplete.success) return canComplete;

    var stepStrsList = setupString.split(' ');

    var stepList = [];
    stepStrsList.forEach(function(stepString) {
      var piece = PIECES.indexOf(stepString.charAt(0));
      var location = stepString.substring(1,3);

      stepList.push({string:stepString}); //need to make adding pieces correspond to the step object

      board[SQUARES[location]] = piece;
    });

    var currentFen = generate_fen();
    boardHistory.push(currentFen);
    colorToMove = (colorToMove === GOLD) ? SILVER : GOLD;
    stepsLeft = 4;
    moveHistory.push(stepList);
    ongoingMove = [];
    halfmoveNumber += 1;
    return {success:true};
  }

  //adds trap step to list of moves if any pieces trapped
  //NEEDS TESTING!!!!!!
  function remove_trapped() {
    for(trap in TRAPS) {
      var sqNum = TRAPS[trap];
      var piece = get_piece_on_square(sqNum);
      if(piece === EMPTY) continue;
      var neighbors = get_neighboring_pieces(sqNum);
      var hasFriendlyNeighbor = false;

      for(var i=0;i<neighbors.length;i++) {
        var neighbor = neighbors[i]['piece'];
        if((piece & COLOR) === (neighbor & COLOR)) {
          hasFriendlyNeighbor = true;
        }
      }

      if(!hasFriendlyNeighbor) {
        var name = PIECES.charAt(piece);
        //ongoingMove.push({piece:piece,squareNum:sqNum,direction:'x', string:name + trap + 'x'});
        ongoingMove.push(ArimaaStep(piece, sqNum, 'x'));
        remove_piece_from_square(sqNum);
      }
    }
  }

  //move is a list of step strings
  //need to call complete move afterwards
  function add_move(move) {
    var isValid = true;
    var badStep = "";

    for(var i=0;i<move.length;i++) {
      var step = move[i];
      if(!add_step(step).success) {
        badStep = step;
        isValid = false;
      }
    }

    if(!isValid) {
      for(var i=0;i<move.length;i++) {
        undo_step(); //undoing more steps than added is safe
      }
      return {success: false, reason: "bad step" + badStep};
    }
    return {success: true};
  }

  //assumes valid square_num
  function get_neighboring_pieces(squareNum) {
    var pieces = [];
    var npiece = get_piece_on_square(squareNum+DIRECTIONS.NORTH);
    var spiece = get_piece_on_square(squareNum+DIRECTIONS.SOUTH);
    var epiece = get_piece_on_square(squareNum+DIRECTIONS.EAST);
    var wpiece = get_piece_on_square(squareNum+DIRECTIONS.WEST);

    if(npiece) pieces.push({piece:npiece,offset:DIRECTIONS.NORTH});
    if(spiece) pieces.push({piece:spiece,offset:DIRECTIONS.SOUTH});
    if(epiece) pieces.push({piece:epiece,offset:DIRECTIONS.EAST});
    if(wpiece) pieces.push({piece:wpiece,offset:DIRECTIONS.WEST});

    return pieces;
  }

  //doesn't take into account who's turn to move, only color of piece on square
  //maybe change it later?
  function is_frozen(squareNum) {
    var piece = get_piece_on_square(squareNum);
    if(piece === EMPTY) {return false;}

    var neighbors = get_neighboring_pieces(squareNum);
    var nextToLargerEnemy = false;
    for(var i=0;i<neighbors.length;i++) {
      var neighbor = neighbors[i]['piece'];
      if((piece & COLOR) === (neighbor & COLOR)) {
        return false;
      } else if( (neighbor & DECOLOR) > (piece & DECOLOR)  ) {
        nextToLargerEnemy = true;
      }

    }
    return nextToLargerEnemy;
  }

  function is_adjacent_to(squareNum, otherSquareNum) {
    var d = squareNum - otherSquareNum;
    return (d==DIRECTIONS.NORTH) || (d==DIRECTIONS.SOUTH)
                 || (d==DIRECTIONS.EAST)
                 || (d==DIRECTIONS.WEST);
  }

  /************************************
   * can_be_pushed and can_be_pulled are only dependent on color;
   * no checks on steps left, etc
   ***********************************/
  function can_be_pushed(squareNum) {
    var piece = board[squareNum];
    if(piece === EMPTY) return false;
    if(((piece & COLOR)>>3) == colorToMove) return false;

    var neighbors = get_neighboring_pieces(squareNum);
    for(var i=0;i<neighbors.length;i++) {
      var neighbor = neighbors[i];
      if( ((neighbor['piece'] & COLOR) != (piece & COLOR)) //different color, & has higher precedence than !=
        && (!is_frozen(squareNum+neighbor['offset'])) //frozen pieces can't push
        && ((neighbor['piece'] & DECOLOR) > (piece & DECOLOR)) //must be a heavier piece
      ) {
        return true;
      }
    }
    return false;
  }

  //TEST THIS FUNCTION
  function can_be_pulled(squareNum) {
    var piece = board[squareNum];
    if(piece === EMPTY) return false;
    if(((piece & COLOR)>>3) == colorToMove) return false;
    if(ongoingMove.length == 0) return false;

    var prevStep = get_prev_step_no_caps();

    if(prevStep['completedPush']) return false; //a piece that just pushed can't also pull

    var prevSquareNum = prevStep['squareNum'];
    var pullingPiece = prevStep['piece'];
    if(!is_adjacent_to(squareNum, prevSquareNum)) return false;
    if((pullingPiece & DECOLOR) == (piece & DECOLOR)) return false; //this check could be better?
    return (pullingPiece & DECOLOR) > (piece & DECOLOR);
  }

  //NEEDS SO MUCH TESTING!!!!!
  function generate_moves() {
    var moves = [];
    var uniquePositions = {};

    function dls(depth) {
      if(depth === 0) return;
      var stack = generate_steps();
      for(var i=0;i<stack.length;i++) {
        var s = stack[i]['string'];
        var a = add_step(s);
        if(!a) {  //TODO We should remove this later since this should never happen
          console.log("/???");
          console.log(ascii());
          console.log(s);
          console.log('=========');
        }
        if(can_complete_move().success) {
          moves.push(ongoingMove.slice());
        }
        dls(depth-1);
        undo_step();
      }
    }

    dls(stepsLeft);
    return moves;
  }

  //returns all possible steps from current position
  function generate_steps() {
    var steps = [];
    for(var square in SQUARES) {
      var sqNum = SQUARES[square];
      steps = steps.concat(generate_steps_for_piece_on_square(sqNum));
    }
    return steps;
  }

  function has_uncompleted_push() {
    if(ongoingMove.length) {
      var prevStep = get_prev_step_no_caps();
      if(prevStep['push']) {
        return true;
      }
    }
    return false;
  }

  //eventually, since we will need to call generate_moves at the beginning
  //of every move, we can skip some of the checking and just add steps if its
  //in the list of possible moves after splitting the string to a step obj

  //also need better support for using the step obj

  //returns a 'stepresult' = {success: t/f, stepsLeft: 0-3, step: stepobj}
  function add_step(stepString) {
    var piece = PIECES.indexOf(stepString.charAt(0));
    var location = stepString.charAt(1)+stepString.charAt(2);
    var direction = stepString.charAt(3);

    //we already implicitly add the trap step, so trying to add it in later would be an error
    //since we would try to remove the piece twice
    //however this would create bad things where we try to remove invalid traps
    //eventually, we should have a separate function addstepwithouttraps or something
    //to better fit this
    //or maybe require an explicit step to remove from traps where the only step possible
    //is to x the trapped piece
    if(direction === 'x') return {success:true};

    if(!stepsLeft) return {success:false, stepsLeft: stepsLeft};

    var squareNum = SQUARES[location];
    //var stepObj = {piece:piece,squareNum:squareNum,direction:direction,string:stepString};
    var stepObj = ArimaaStep(piece, squareNum, direction);

    if(piece === GRABBIT && direction === 's') return {success:false, stepsLeft: stepsLeft};
    if(piece === SRABBIT && direction === 'n') return {success:false, stepsLeft: stepsLeft};

    if(ongoingMove.length) {
      var prevStep = get_prev_step_no_caps();
      if(prevStep['push']) {
        if(prevStep['squareNum'] == squareNum+DIRECTIONS[direction] &&
          ((piece & COLOR) >> 3) == colorToMove &&
          (piece & DECOLOR) > (prevStep['piece'] & DECOLOR)) {
          stepObj['completedPush'] = true;
        }
        else {
          return {success:false, stepsLeft: stepsLeft};
        }
      }
    }

    //do this to prevent rebasing moves onto a changed board state
    if(stepStack.length > 0) {
      var s = stepStack.pop();
      if(s['string'] !== stepString) {
        stepStack = [];
      }
    }

    var p = get_piece_on_square(squareNum);
    if(p !== piece) return {success:false, stepsLeft: stepsLeft};

    if(direction === 'x') {
      //TODO CHECK IF WE ACTUALLY SHOULD REMOVE THIS PIECE LATER
      remove_piece_from_square(squareNum);
      return {success:true, stepsLeft: stepsLeft, step: stepObj};
    }

    var nextSquare = squareNum+DIRECTIONS[direction];
    //trying to move off the board or into occupied territory
    if(  (nextSquare & 0x88) != 0  ||  board[nextSquare] !== EMPTY )
    {
      return {success:false, stepsLeft: stepsLeft};
    }

    //diff color
    if(((piece & COLOR) >> 3) != colorToMove) {
      if(can_be_pulled(squareNum)) {
        //do nothing
      } else if(can_be_pushed(squareNum)) {
        stepObj.push = true;
      } else {
        return {success:false, stepsLeft: stepsLeft};
      }
    }

    remove_piece_from_square(squareNum);
    place_piece(p, nextSquare);

    ongoingMove.push(stepObj);

    //CHECK TRAPS HERE!!!!
    remove_trapped();
    stepsLeft -= 1;
    return {success:true, stepsLeft: stepsLeft, step: stepObj};
  }

  //currently assumes valid square

  //generate steps actually doesn't add special flags (push, pull, completedPush)
  function generate_steps_for_piece_on_square(squareNum) {
    var piece = board[squareNum];
    var steps = [];
    if(piece === EMPTY || stepsLeft === 0) {
      return steps;
    }

    var prevStep = get_prev_step_no_caps();

    var pieceName = PIECES.charAt(piece);
    var location = square_name(squareNum);

    //actually it might make more sense to add a 'push' object to a step object
    if(prevStep != null && prevStep['push']) {
      if(is_adjacent_to(squareNum, prevStep['squareNum'])) {
        if((piece & DECOLOR) > (prevStep['piece'] & DECOLOR)) {
          var dir = DIRECTIONS[prevStep['squareNum']-squareNum];
          //steps.push({piece:piece,squareNum:squareNum,direction:dir,string:pieceName+location+dir});//need a better way to get the dircetion
          steps.push(ArimaaStep(piece, squareNum, dir));
        }
      }
      return steps;
    }

    //later iterate over the directions
    //same color
    if((piece & COLOR) >> 3 == colorToMove && !is_frozen(squareNum)) {
      if ((((squareNum + DIRECTIONS.NORTH) & 0x88) === 0) && (board[squareNum+DIRECTIONS.NORTH] === EMPTY) && piece !== SRABBIT)
        steps.push(ArimaaStep(piece, squareNum, 'n'));
        //steps.push({piece:piece,squareNum:squareNum,direction:'n',string:pieceName+location+'n'});
      if ((((squareNum + DIRECTIONS.SOUTH) & 0x88) === 0) && (board[squareNum+DIRECTIONS.SOUTH] === EMPTY) && piece !== GRABBIT)
        steps.push(ArimaaStep(piece, squareNum, 's'));
        //steps.push({piece:piece,squareNum:squareNum,direction:'s',string:pieceName+location+'s'});
      if ((((squareNum + DIRECTIONS.EAST) & 0x88) === 0) && (board[squareNum+DIRECTIONS.EAST] === EMPTY))
        steps.push(ArimaaStep(piece, squareNum, 'e'));
        //steps.push({piece:piece,squareNum:squareNum,direction:'e',string:pieceName+location+'e'});
      if ((((squareNum + DIRECTIONS.WEST) & 0x88) === 0) && (board[squareNum+DIRECTIONS.WEST] === EMPTY))
        steps.push(ArimaaStep(piece, squareNum, 'w'));
        //steps.push({piece:piece,squareNum:squareNum,direction:'w',string:pieceName+location+'w'});
    } else { //enemy piece
      if(can_be_pushed(squareNum) && stepsLeft > 1) {
        if ((((squareNum + DIRECTIONS.NORTH) & 0x88) === 0) && (board[squareNum+DIRECTIONS.NORTH] === EMPTY))
          steps.push(ArimaaStep(piece, squareNum, 'n'));
          //steps.push({piece:piece,squareNum:squareNum,direction:'n',string:pieceName+location+'n'});
        if ((((squareNum + DIRECTIONS.SOUTH) & 0x88) === 0) && (board[squareNum+DIRECTIONS.SOUTH] === EMPTY))
          steps.push(ArimaaStep(piece, squareNum, 's'));
          //steps.push({piece:piece,squareNum:squareNum,direction:'s',string:pieceName+location+'s'});
        if ((((squareNum + DIRECTIONS.EAST) & 0x88) === 0) && (board[squareNum+DIRECTIONS.EAST] === EMPTY))
          steps.push(ArimaaStep(piece, squareNum, 'e'));
          //steps.push({piece:piece,squareNum:squareNum,direction:'e',string:pieceName+location+'e'});
        if ((((squareNum + DIRECTIONS.WEST) & 0x88) === 0) && (board[squareNum+DIRECTIONS.WEST] === EMPTY))
          steps.push(ArimaaStep(piece, squareNum, 'w'));
          //steps.push({piece:piece,squareNum:squareNum,direction:'w',string:pieceName+location+'w'});
      } else if(can_be_pulled(squareNum)) { //use else if to prevent adding duplicate step
        var dir = DIRECTIONS[prevStep['squareNum']-squareNum];
        //steps.push({piece:piece,squareNum:squareNum,direction:dir,string:pieceName+location+dir});
        steps.push(ArimaaStep(piece, squareNum, dir));
      }
    }
    return steps;
  }

  function place_piece(piece, squareNum) {
    board[squareNum] = piece;
    return true;
  }

  //returns the piece removed
  //returns false for invalid square and EMPTY for empty square
  function remove_piece_from_square(squareNum) {
    if(squareNum & 0x88) {return false;}
    var p = get_piece_on_square(squareNum);
    board[squareNum] = EMPTY;
    return p;
  }

  //supports short fens generated by http://arimaa.com/arimaa/notconv/old/boardtools.php
  //example: http://arimaa.com/arimaa/notconv/old/boardimg.php?orient=s&size=500&imgtype=jpeg&ranks=1rr1r1rr/1d3c/3c//1H1r1Ehd/1M1e2DC/RRrRRDRR/2hR1R
  //should only be called at the beginning
  function parse_fen(fen) {
    var b = new Array(128);
    for(var i=0;i<128;i++) {b[i] = 0;}
    if(fen.charAt(0) == '/') {
      fen = '/'+fen;
    }
    if(fen.charAt(fen.length-1) == '/') {
      fen += '/';
    }
    while(fen.indexOf('//') != -1) {
      fen = fen.replace('//','/8/');
    }

    var i=0;
    var index=0;
    while(i<64 && index < fen.length) {
      var x = i % 8;
      var y = Math.floor(i/8);
      var c = fen.charAt(index);

      index++;

      var p = PIECES.indexOf(c);
      var n = '12345678'.indexOf(c);

      if(c == '/') {
        i = Math.floor((i-1)/8)*8+8;
      } else if(p != -1) {
        b[16*y+x] = p;
        i++;
      } else if(n != -1) {
        i+=n+1;
      }
    }
    board = b;
    return b;
  }

  function generate_fen() {
    var empty = 0;
    var fen = '';
    for (var i = SQUARES.a8; i <= SQUARES.h1; i++) {
      if (board[i] === EMPTY) {
        empty++;
      } else {
        if (empty > 0) {
          fen += empty;
          empty = 0;
        }
        var piece = board[i];
        fen += PIECES.charAt(piece);
      }
      if ((i + 1) & 0x88) {
        if (empty > 0) {
          fen += empty;
        }
        if (i !== SQUARES.h1) {
          fen += '/';
        }
        empty = 0;
        i += 8;
      }
    }
    return fen;
  }

  //TEST THIS!!!
  function is_goal() {
    var gGoal = false;
    for (var ix = 0x00; ix < 0x08; ix++) {
      if (board[ix] == GRABBIT) { gGoal = true; break }
    }
    var sGoal = false;
    for (var ix = 0x70; ix < 0x78; ix++) {
      if (board[ix] == SRABBIT) { sGoal = true; break }
    }
    if (gGoal || sGoal) {
      if (colorToMove == SILVER) { //reversed since we check at the beginning of each halfmove for the player who just completed the turn
        if (sGoal) { return -1; } else { return 1; }
      } else {
        if (gGoal) { return 1; } else { return -1; }
      }
    } else {
      return 0;
    }
  }

  //TEST THIS TOO!!!
  //also make it less ugly
  function is_elimination() {
    var myRabbitCount = 0;
    var oppRabbitCount = 0;
    for(var sq in SQUARES) {
      var sqNum = SQUARES[sq];
      var piece = get_piece_on_square(sqNum);
      if(piece === GRABBIT) {
        if(colorToMove === GOLD) {
          oppRabbitCount++;
        } else {
          myRabbitCount++;
        }
      } else if(piece === SRABBIT) {
        if(colorToMove === GOLD) {
          myRabbitCount++;
        } else {
          oppRabbitCount++;
        }
      }
    }
    if(oppRabbitCount === 0) return 1;
    if(myRabbitCount === 0) return -1;
    return 0;
  }

  //only check for opponent
  function is_immobilization() {
    if(generate_steps().length == 0) return 1;
    return 0;
  }

  //IMPLEMENT THIS AT SOME POINT!!!!!!!
  function is_repetition() {

  }

  /*
    FROM arimaa.com
    The order of checking for win/lose conditions is as follows assuming player A just made the move and player B now needs to move:
    Check if a rabbit of player A reached goal. If so player A wins.
    Check if a rabbit of player B reached goal. If so player B wins.
    Check if player B lost all rabbits. If so player A wins.
    Check if player A lost all rabbits. If so player B wins.
    Check if player B has no possible move (all pieces are frozen or have no place to move). If so player A wins.
    Check if the only moves player B has are 3rd time repetitions. If so player A wins.
  */


  //0 is no victory, 1 is victory, -1 is loss
  //TODO: Implement no moves available b/c 3-fold repetition check
  //Maybe change result to color????
  function check_victory() {
    var goal = is_goal();
    if(goal !== 0) {
      return {result:goal,reason:'g'};
    }
    var elim = is_elimination();
    if(elim !== 0) {
      return {result:elim,reason:'e'};
    }
    var imm = is_immobilization();
    if(imm !== 0) {
      return {result:imm,reason:'m'};
    }
    //IMPLEMENT REPETITION CHECK!!!
    return {result:0};
  }

  function get_piece_on_square(squareNum) {
    if(squareNum & 0x88) return false;
    return board[squareNum];
  }

  function get_owner_of_square(squareNum) {
    if(squareNum & 0x88) return NULL_COLOR;
    if(board[squareNum] === EMPTY) return NULL_COLOR;
    return (board[squareNum] & COLOR);
  }


  function ascii() {
    var s = ' +-----------------+\n';
    var row = "87654321";
    var file = "abcdefgh";
    for(var i=0;i<8;i++) {
      s += row[i] + "| ";
      for(var j=0;j<8;j++) {
        var sq = file[j] + row[i];
        s += PIECES.charAt(get_piece_on_square(SQUARES[sq]));
        s += " ";
      }
      s += "|\n";
    }
    s += ' +-----------------+\n';
    s += '   a b c d e f g h\n';
    return s;
  }

  function get_move_list_string() {
    var s = '';

    for(var i=0;i<moveHistory.length;i++) {
      s += 1+Math.floor(i/2);
      if(i%2) {
        s += "s ";
      } else {
        s += "g ";
      }
      var move = moveHistory[i];
      for(var j=0;j<move.length;j++) {
        var step = move[j];
        s += step['string'];
      }
      s += "\n";
    }
    return s;
  }

  return {
    PIECES: Piece,
    SQUARES: SQUARES,

    is_goal: function() {
      return is_goal();
    },

    is_frozen: function(square) {
      return is_frozen(square);
    },

    //TODO TEST THIS LOLOLOLOL
    is_empty: function(square) {
      return this.get_piece_on_square(square) === EMPTY;
    },

    //Ra1 Rb1 ...
    setup: function(setupString) {
      return setup(setupString);
    },
    can_setup: function() {
      return can_setup();
    },

    //TODO this is ugly!!
    add_move_string: function(moveString) {
      undo_ongoing_move();

      var stepsList = moveString.split(' ');

      if(moveHistory.length === 0) return setup(moveString);
      else if(moveHistory.length === 1) return setup(moveString);
      else {
        var result = add_move(stepsList);
        if(!result.success)
          return result;
        return complete_move();
      }
    },

    //move is a list of step strings
    add_move: function(move) {
      return add_move(move);
    },

    add_step: function(stepString) {
      return add_step(stepString);
    },

    can_undo_step: function() {
      return can_undo_step();
    },

    can_redo_step: function() {
      return can_redo_step();
    },

    undo_step: function() {
      return undo_step();
    },

    redo_step: function() {
      return redo_step();
    },

    undo_ongoing_move: function() {
      return undo_ongoing_move();
    },

    redo_ongoing_move: function() {
      return redo_ongoing_move();
    },

    complete_move: function() {
      return complete_move();
    },

    can_complete_move: function() {
      return can_complete_move();
    },

    has_uncompleted_push() {
      return has_uncompleted_push();
    },

    //note: currently, you can add more steps after victory,
    //which allows you to un-win
    check_victory: function() {
      return check_victory();
    },

    //probably should rename one of these functions
    //don't use this anymore, set position in options
    /*
    set_position: function(fen) {
      return parse_fen(fen);
    },*/


    //probably rename this later too
    get_fen: function() {
      return generate_fen();
    },

    get_move_list: function() {
      return moveHistory;
    },

    get_move_list_string: function() {
      return get_move_list_string();
    },

    can_be_pushed: function(square) {
      var squareNum = SQUARES[square];
      return can_be_pushed(squareNum);
    },

    get_ongoing_move: function() {
      return ongoingMove;
    },

    get_ongoing_move_string: function() {
      return ongoingMove.map(function(m) {return m.string;}).join(' ');
    },

    //TODO TEST THIS!!!!
    get_turn_name: function() {
      var c = halfmoveNumber % 2 ? 's' : 'g';
      return (1+Math.floor(halfmoveNumber/2)) + c; //casting
    },

    get_halfmove_number: function() {
      return halfmoveNumber;
    },

    generate_moves: function() {
      return generate_moves();
    },

    generate_moves_strings: function() {
      var moves = generate_moves();
      var moveStrings = [];
      for(var i=0;i<moves.length;i++) {
        var move = moves[i];
        //console.log(move);
        var current_move_string = [];
        for(var j=0;j<move.length;j++) {
          var s = move[j];
          current_move_string.push(s['string']);
        }
        moveStrings.push(current_move_string);
      }
      return moveStrings;
    },

    generate_steps: function() {
      return generate_steps();
    },

    generate_steps_strings: function() {
      var stepStrings = [];
      var steps = generate_steps();
      for(var i=0;i<steps.length;i++) {
        var step = steps[i];
        stepStrings.push(step['string']);
      }
      return stepStrings;
    },

    generate_steps_for_piece_on_square: function(square) {
      var squareNum = SQUARES[square];
      return generate_steps_for_piece_on_square(squareNum);
    },

    get_piece_on_square: function(square) {
      var squareNum = SQUARES[square];
      return get_piece_on_square(squareNum);
    },

    get_owner_of_square: function(square) {
      var squareNum = SQUARES[square];
      return get_owner_of_square(squareNum);
    },

    place_piece: function(piece, square) {
      if(!(square in SQUARES)) return false;
      if(piece == " ") return false;
      if(PIECES.indexOf(piece) == -1) return false;
      if(board[SQUARES[square]]) return false;
      return place_piece(piece, SQUARES[square]);
    },

    square_name: function(squareNum) {
      if(squareNum & 0x88) return "";
      return square_name(squareNum);
    },

    ascii: function() {
      return ascii();
    },

    log_ascii: function() {
      console.log(ascii());
    },

    //TODO: Implement this
    clear: function() {
      return null;
    },

    //DEBUGGING USE ONLY!!!!!!!!!!
    log_locals: function() {
      console.log("board:\n",board.toString()); //use toString to prevent node from putting 1 entry per line
      console.log("board history:\n",boardHistory);
      console.log("move history:\n", moveHistory);
      console.log("ongoing move:\n",ongoingMove);
      console.log("halfmove num ",halfmoveNumber);
      console.log("steps left ",stepsLeft);
      console.log("color to move",colorToMove);
    }
  };
};

module.exports = Arimaa;
