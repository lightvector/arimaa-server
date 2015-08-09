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
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp

object ChatSystem {
  //Leave chat if it's been this many seconds with no activity
  val INACTIVITY_TIMEOUT: Double = 120.0
  //Max lines at a time to return in a single query
  val READ_MAX_LINES: Int = 5000
  //Timeout for a single get query for chat messages
  val GET_TIMEOUT: Double = 15.0
  //Timeout for akka ask queries
  val AKKA_TIMEOUT: Timeout = new Timeout(20 seconds)
  //Period for checking timeouts in a chat even if nothing happens
  val CHAT_CHECK_TIMEOUT_PERIOD: Double = 120.0

  val NO_CHANNEL_MESSAGE = "No such chat channel, or not authorized"
  val NO_LOGIN_MESSAGE = "Not logged in, or timed out due to inactivity"

  type Channel = String

  val table = TableQuery[ChatTable]
}

import ChatSystem.Channel

case class ChatLine(id: Long, channel: Channel, username: Username, text: String, timestamp: Timestamp)

//---CHAT SYSTEM----------------------------------------------------------------------------------

/** Class representing a whole chat system composed of various channels, backed by a database. */
class ChatSystem(val db: Database, val actorSystem: ActorSystem)(implicit ec: ExecutionContext) {

  private var channelData: Map[Channel,ActorRef] = Map()
  implicit val timeout = ChatSystem.AKKA_TIMEOUT

  private def openChannel(channel: Channel): ActorRef = this.synchronized {
    channelData.get(channel) match {
      case Some(cc) => cc
      case None =>
        val cc = actorSystem.actorOf(Props(new ChatChannel(channel,db,actorSystem)))
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
  def join(channel: Channel, username: Username): Future[ChatAuth] = {
    val cc = openChannel(channel)
    (cc ? ChatChannel.Join(username)).map(_.asInstanceOf[ChatAuth])
  }

  /** Leave the specified chat channel. Failed if not logged in. */
  def leave(channel: Channel, chatAuth: ChatAuth): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Leave(chatAuth)).map(_.asInstanceOf[Unit])
    }
  }

  /** Post in the specified chat channel. Failed if not logged in. */
  def post(channel: Channel, chatAuth: ChatAuth, text:String): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Post(chatAuth,text)).map(_.asInstanceOf[Unit])
    }
  }

  /** Heartbeat the specified chat channel to avoid logout from inactivity. Failed if not logged in. */
  def heartbeat(channel: Channel, chatAuth: ChatAuth): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Heartbeat(chatAuth)).map(_.asInstanceOf[Unit])
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

  //Replies with ChatAuth
  case class Join(username: Username)
  //Replies with Unit
  case class Leave(chatAuth: ChatAuth)
  //Replies with Unit
  case class Post(chatAuth: ChatAuth, text:String)
  //Replies with Unit
  case class Heartbeat(chatAuth: ChatAuth)

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
class ChatChannel(val channel: Channel, val db: Database, val actorSystem: ActorSystem) extends Actor with Stash {

  //Fulfilled and replaced on each message - this is the mechanism by which
  //queries can block and wait for chat activity
  var nextMessage: Promise[ChatLine] = Promise()
  var nextId: Long = 0L

  //Holds messages that we are not confident are in the database yet, avoiding
  //races between writes to the chat and queries to read the new lines posted
  var messagesNotYetInDB: Queue[ChatLine] = Queue()

  //Tracks who is logged in to this chat channel
  val logins: LoginTracker = new LoginTracker(None,ChatSystem.INACTIVITY_TIMEOUT)
  //Most recent time anything happened in this channel
  var lastActive = Timestamp.get

  //Whether or not we started the loop that checks timeouts for the chat
  var timeoutCycleStarted = false

  case class Initialized(maxId: Try[Long])
  case class DBWritten(upToId: Long)
  case class DoTimeouts()

  implicit val timeout = ChatSystem.AKKA_TIMEOUT
  import context.dispatcher

  override def preStart = {
    //Find the maximum chat id in this channel from the database
    val query: Rep[Option[Long]] = ChatSystem.table.filter(_.channel === channel).map(_.id).max
    db.run(query.result).map(_.getOrElse(-1L)).onComplete {
      result => self ! Initialized(result)
    }
    if(!timeoutCycleStarted) {
      timeoutCycleStarted = true
      actorSystem.scheduler.scheduleOnce(ChatSystem.CHAT_CHECK_TIMEOUT_PERIOD seconds, self, DoTimeouts())
    }
  }

  def receive = initialReceive
  def initialReceive: Receive = {
    //Wait for us to have found the maximum chat id
    case Initialized(maxId: Try[Long]) =>
      //TODO test this
      //Raises an exception in the case where we failed to find the max id
      nextId = maxId.get + 1
      //And begin normal operation
      context.become(normalReceive)
      unstashAll()
    case _ => stash()
  }

  def normalReceive: Receive = {
    case ChatChannel.Join(username: Username) =>
      val now = Timestamp.get
      logins.doTimeouts(now)
      val chatAuth = logins.login(username, now)
      lastActive = now
      sender ! (chatAuth : ChatAuth)

    case ChatChannel.Leave(chatAuth: ChatAuth) =>
      val result: Try[Unit] = requiringLogin(chatAuth) { username =>
        logins.logout(username,chatAuth,Timestamp.get)
      }
      replyWith(sender, result)

    case ChatChannel.Post(chatAuth: ChatAuth, text:String) =>
      val result: Try[Unit] = requiringLogin(chatAuth) { username =>
        val line = ChatLine(nextId, channel, username, text, Timestamp.get)
        nextId = nextId + 1

        //Add to queue of lines that we will remember until they show up in the db
        messagesNotYetInDB = messagesNotYetInDB.enqueue(line)

        //Write to DB and on success clear own memory
        //TODO log on error
        val query = ChatSystem.table += line
        db.run(DBIO.seq(query)).foreach { _ =>
          self ! DBWritten(line.id)
        }

        nextMessage.success(line)
        nextMessage = Promise()
      }
      replyWith(sender, result)

    case ChatChannel.Heartbeat(chatAuth: ChatAuth) =>
      val result: Try[Unit] = requiringLogin(chatAuth) { (_ : Username) => () }
      replyWith(sender, result)


    case ChatChannel.Get(
      minId: Option[Long],
      maxId: Option[Long],
      minTime: Option[Timestamp],
      maxTime: Option[Timestamp],
      doWait: Option[Boolean]
    ) =>
      lastActive = Timestamp.get
      val minId_ = minId.getOrElse(nextId - ChatSystem.READ_MAX_LINES)
      val doWait_ = doWait.getOrElse(false)

      def isOk(x:ChatLine): Boolean = {
        x.id >= minId_ &&
        maxId.forall(maxId => x.id <= maxId) &&
        minTime.forall(minTime => x.timestamp >= minTime) &&
        maxTime.forall(maxTime => x.timestamp <= maxTime)
      }

      var query = ChatSystem.table.
        filter(_.channel === channel).
        filter(_.id >= minId_)
      maxId.foreach(maxId => query = query.filter(_.id <= maxId))
      minTime.foreach(minTime => query = query.filter(_.timestamp >= minTime))
      maxTime.foreach(maxTime => query = query.filter(_.timestamp <= maxTime))
      query = query.
        sortBy(_.id).
        take(ChatSystem.READ_MAX_LINES)

      val dbResult: Future[List[ChatLine]] = db.run(query.result).map(_.toList)
      val result: Future[List[ChatLine]] = dbResult.flatMap { dbLines =>
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

    case DBWritten(upToId: Long) =>
      messagesNotYetInDB = messagesNotYetInDB.dropWhile { line =>
        line.id <= upToId
      }

    case DoTimeouts() =>
      val now = Timestamp.get
      //TODO if nobody is logged in for long enough, then shut down this chat!
      logins.doTimeouts(now)
      actorSystem.scheduler.scheduleOnce(ChatSystem.CHAT_CHECK_TIMEOUT_PERIOD seconds, self, DoTimeouts())
  }

  def replyWith[T](sender: ActorRef, result: Try[T]) = {
    result match {
      case Failure(e) => sender ! akka.actor.Status.Failure(e)
      case Success(x) => sender ! x
    }
  }

  def requiringLogin[T](chatAuth: ChatAuth)(f:Username => T) : Try[T] = {
    val now = Timestamp.get
    logins.doTimeouts(now)
    logins.heartbeatAuth(chatAuth,now) match {
      case None => Failure(new Exception(ChatSystem.NO_LOGIN_MESSAGE))
      case Some(username) =>
      lastActive = now
      Success(f(username))
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
