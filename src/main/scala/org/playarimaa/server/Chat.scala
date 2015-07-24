package org.playarimaa.server
import scala.collection.immutable.Queue
import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import slick.driver.H2Driver.api._
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.RandGen.Auth
import org.playarimaa.server.Accounts.Import._

object ChatSystem {
  //Leave chat if it's been this many seconds with no activity
  val INACTIVITY_TIMEOUT: Double = 120.0
  //Max lines at a time to return in a single query
  val READ_MAX_LINES: Int = 5000
  //Timeout for a single get query for chat messages
  val GET_TIMEOUT: Double = 15.0
  //Timeout for akka ask queries
  val AKKA_TIMEOUT: Timeout = new Timeout(20 seconds)

  val NO_CHANNEL_MESSAGE = "No such chat channel, or not authorized"
  val NO_LOGIN_MESSAGE = "Not logged in, or timed out due to inactivity"

  type Channel = String
}

import ChatSystem.Channel

case class ChatLine(id: Long, channel: Channel, username: Username, text: String, timestamp: Timestamp)

//---CHAT SYSTEM----------------------------------------------------------------------------------

/** Class representing a whole chat system composed of various channels, backed by a database. */
class ChatSystem(val db: Database, val actorSystem: ActorSystem)(implicit ec: ExecutionContext) {

  private var channelData: Map[Channel,ActorRef] = Map()
  private val chatDB = actorSystem.actorOf(Props(new ChatDB(db)))
  implicit val timeout = ChatSystem.AKKA_TIMEOUT

  private def openChannel(channel: Channel): ActorRef = this.synchronized {
    channelData.get(channel) match {
      case Some(cc) => cc
      case None =>
        val cc = actorSystem.actorOf(Props(new ChatChannel(channel,chatDB,actorSystem)))
        channelData = channelData + (channel -> cc)
        cc
    }
  }

  private def withChannel[T](channel: Channel)(f:ActorRef => Future[T]) : Future[T] = {
    val cc = this.synchronized { channelData.get(channel) }
    cc match {
      case None => Future.failed(new Exception(ChatSystem.NO_CHANNEL_MESSAGE))
      case Some(cc) => f(cc)
    }
  }

  /** Join the specified chat channel */
  def join(channel: Channel, username: Username): Future[Auth] = {
    val cc = openChannel(channel)
    (cc ? ChatChannel.Join(username)).map(_.asInstanceOf[Auth])
  }

  /** Leave the specified chat channel. Failed if not logged in. */
  def leave(channel: Channel, username: Username, auth: Auth): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Leave(username,auth)).map(_.asInstanceOf[Unit])
    }
  }

  /** Post in the specified chat channel. Failed if not logged in. */
  def post(channel: Channel, username: Username, auth: Auth, text:String): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Post(username,auth,text)).map(_.asInstanceOf[Unit])
    }
  }

  /** Heartbeat the specified chat channel to avoid logout from inactivity. Failed if not logged in. */
  def heartbeat(channel: Channel, username: Username, auth: Auth): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Heartbeat(username,auth)).map(_.asInstanceOf[Unit])
    }
  }

  /** Get the specified range of lines of chat from a channel.
    * If [doWait] is true and there are no lines meeting the criteria, wait a short time,
    * returning when a new line is posted or upon timeout.
    *
    * [minId] defaults to the current end of chat minus [READ_MAX_LINES]
    * [doWait] defaults to false.
    * All other optional parameters default to having no effect.
    */
  def get(
    channel: Channel,
    minId: Option[Long],
    maxId: Option[Long],
    minTime: Option[Timestamp],
    maxTime: Option[Timestamp],
    doWait: Option[Boolean]
  ) : Future[List[ChatLine]] = {
    val cc = openChannel(channel)
    (cc ? ChatChannel.Get(minId,maxId,minTime,maxTime,doWait)).map(_.asInstanceOf[List[ChatLine]])
  }
}

//---CHAT CHANNEL----------------------------------------------------------------------------------

object ChatChannel {

  //ACTOR MESSAGES---------------------------------------------------------

  //Replies with Auth
  case class Join(username: Username)
  //Replies with Unit
  case class Leave(username: Username, auth: Auth)
  //Replies with Unit
  case class Post(username: Username, auth: Auth, text:String)
  //Replies with Unit
  case class Heartbeat(username: Username, auth: Auth)

  /** [minId] defaults to the current end of chat minus [READ_MAX_LINES]
    * [doWait] defaults to false.
    * All other optional parameters default to having no effect.
    * Replies with List[ChatLine]
    */
  case class Get(
    minId: Option[Long],
    maxId: Option[Long],
    minTime: Option[Timestamp],
    maxTime: Option[Timestamp],
    doWait: Option[Boolean]
  )
}

/** An actor that handles an individual channel that people can chat in */
class ChatChannel(val channel: Channel, val chatDB: ActorRef, val actorSystem: ActorSystem) extends Actor with Stash {

  //Fulfilled and replaced on each message - this is the mechanism by which
  //queries can block and wait for chat activity
  var nextMessage: Promise[ChatLine] = Promise()
  var nextId: Long = 0L

  //Holds messages that we are not confident are in the database yet, avoiding
  //races between writes to the chat and queries to read the new lines posted
  var messagesNotYetInDB: Queue[ChatLine] = Queue()

  //Most recent time anything happened in this channel
  val logins: LoginTracker = new LoginTracker(ChatSystem.INACTIVITY_TIMEOUT)

  case class Initialized(nextId:Try[Long])
  case class DBWritten(upToId:Long)

  implicit val timeout = ChatSystem.AKKA_TIMEOUT
  import context.dispatcher

  override def preStart = {
    (chatDB ? ChatDB.GetNextId(channel)).onComplete {
      result => self ! Initialized(result.map(_.asInstanceOf[Long]))
    }
  }

  def receive = initialReceive

  def initialReceive: Receive = {
    case Initialized(id: Try[Long]) =>
      nextId = id.get + 1
      context.become(normalReceive)
      unstashAll()
    case _ => stash()
  }

  def normalReceive: Receive = {
    case ChatChannel.Join(username: Username) =>
      val auth = logins.login(username, Timestamp.get)
      sender ! (auth : Auth)

    case ChatChannel.Leave(username: Username, auth: Auth) =>
      val result: Try[Unit] = requiringLogin(username,auth) { () =>
        logins.logout(username,auth,Timestamp.get)
      }
      replyWith(sender, result)

    case ChatChannel.Post(username: Username, auth: Auth, text:String) =>
      val result: Try[Unit] = requiringLogin(username,auth) { () =>
        val line = ChatLine(nextId, channel, username, text, Timestamp.get)
        nextId = nextId + 1

        //Add to queue of lines that we will remember until they show up in the db
        messagesNotYetInDB = messagesNotYetInDB.enqueue(line)

        //Write to DB and on success clear own memory
        val written: Future[Any] = (chatDB ? ChatDB.WriteLine(line))
        written.foreach { _ =>
          self ! DBWritten(line.id)
        }

        nextMessage.success(line)
        nextMessage = Promise()
      }
      replyWith(sender, result)

    case ChatChannel.Heartbeat(username: Username, auth: Auth) =>
      val result: Try[Unit] = requiringLogin(username,auth) { () => () }
      replyWith(sender, result)


    case ChatChannel.Get(
      minId: Option[Long],
      maxId: Option[Long],
      minTime: Option[Timestamp],
      maxTime: Option[Timestamp],
      doWait: Option[Boolean]
    ) =>
      logins.setLastActive(Timestamp.get)
      val minId_ = minId.getOrElse(nextId - ChatSystem.READ_MAX_LINES)
      val doWait_ = doWait.getOrElse(false)

      def isOk(x:ChatLine): Boolean = {
        x.id >= minId_ &&
        maxId.forall(maxId => x.id <= maxId) &&
        minTime.forall(minTime => x.timestamp >= minTime) &&
        maxTime.forall(maxTime => x.timestamp <= maxTime)
      }

      val query = ChatDB.ReadLines(channel,minId_,maxId,minTime,maxTime)
      val result: Future[List[ChatLine]] = (chatDB ? query).flatMap { result =>
        val dbLines = result.asInstanceOf[List[ChatLine]]
        val extraLines = messagesNotYetInDB.toList.filter(isOk)
        val lines =
          (dbLines,extraLines) match {
            case (Nil,Nil) => Nil
            case (  _,Nil) => dbLines
            case (Nil,  _) => extraLines
            case (  _,  _) =>
              val lastId = dbLines.last.id
              dbLines ++ extraLines.dropWhile(_.id <= lastId)
          }

        if(doWait_ && lines.isEmpty) {
          val result = nextMessage.future.map { x => List(x).filter(isOk) }
          val timeout = akka.pattern.after(ChatSystem.GET_TIMEOUT seconds, actorSystem.scheduler)(Future(List()))
          Future.firstCompletedOf(List(result,timeout))
        }
        else {
          Future(lines)
        }
      }
      result pipeTo sender
  }

  def replyWith[T](sender: ActorRef, result: Try[T]) = {
    result match {
      case Failure(e) => sender ! akka.actor.Status.Failure(e)
      case Success(x) => sender ! x
    }
  }


  def requiringLogin[T](username: Username, auth: Auth)(f:() => T) : Try[T] = {
    if(logins.requireLogin(username,auth,Timestamp.get))
      Success(f())
    else
      Failure(new Exception(ChatSystem.NO_LOGIN_MESSAGE))
  }

  def clearMessagesNotYetInDB(upToId: Long) {
    messagesNotYetInDB = messagesNotYetInDB.dropWhile{ line =>
      line.id <= upToId
    }
  }


}

//CHAT DATABASE---------------------------------------------------------------------

object ChatDB {

  //ACTOR MESSAGES---------------------------------------------------------

  //Replies with Long
  case class GetNextId(channel: Channel)
  //Replies with Unit when result committed
  case class WriteLine(line: ChatLine)
  //Replies with List[ChatLine]
  case class ReadLines(channel: Channel, minId: Long, maxId: Option[Long], minTime: Option[Timestamp], maxTime: Option[Timestamp])


  val table = TableQuery[ChatTable]
}

/** An actor that handles chat-related database queries */
class ChatDB(val db: Database)(implicit ec: ExecutionContext) extends Actor {

  def receive = {
    case ChatDB.GetNextId(channel) => {
      val query: Rep[Option[Long]] = ChatDB.table.filter(_.channel === channel).map(_.id).max
      //println("Generated SQL:\n" + query.result.statements)

      val result: Future[Long] = db.run(query.result).map(_.getOrElse(-1L))
      result.pipeTo(sender)
    }

    case ChatDB.WriteLine(line) => {
      val query = ChatDB.table += line

      val result: Future[Unit] = db.run(DBIO.seq(query))
      result.pipeTo(sender)
    }

    case ChatDB.ReadLines(channel, minId, maxId, minTime, maxTime) => {
      var query = ChatDB.table.
        filter(_.channel === channel).
        filter(_.id >= minId)
      maxId.foreach(maxId => query = query.filter(_.id <= maxId))
      minTime.foreach(minTime => query = query.filter(_.timestamp >= minTime))
      maxTime.foreach(maxTime => query = query.filter(_.timestamp <= maxTime))
      query = query.
        sortBy(_.id).
        take(ChatSystem.READ_MAX_LINES)

      val result: Future[List[ChatLine]] = db.run(query.result).map(_.toList)
      result.pipeTo(sender)
    }
  }

}

class ChatTable(tag: Tag) extends Table[ChatLine](tag, "chatTable") {
  def id = column[Long]("id")
  def channel = column[String]("channel")
  def username = column[String]("username")
  def text = column[String]("text")
  def timestamp = column[Double]("time")

  //The * projection (e.g. select * ...) auto-transforms the tuple to the case class
  def * = (id, channel, username, text, timestamp) <> (ChatLine.tupled, ChatLine.unapply)
}
