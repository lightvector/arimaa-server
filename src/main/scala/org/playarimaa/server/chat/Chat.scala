package org.playarimaa.server.chat
import scala.collection.immutable.Queue
import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt, DurationDouble}
import scala.util.{Try, Success, Failure}
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import org.playarimaa.server.DatabaseConfig.driver.api._
import slick.lifted.{PrimaryKey,ProvenShape}
import org.slf4j.{Logger, LoggerFactory}
import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.SimpleUserInfo
import org.playarimaa.server.TimeBuckets
import org.playarimaa.server.{LoginTracker,SiteLogin,Timestamp}
import org.playarimaa.server.Timestamp.Timestamp

object ChatSystem {
  //Leave chat if it's been this many seconds with no activity (including heartbeats)
  val INACTIVITY_TIMEOUT: Double = 120.0
  //Max lines at a time to return in a single query
  val READ_MAX_LINES: Int = 5000
  //Timeout for a single get query for chat messages
  val GET_TIMEOUT: Double = 15.0
  //Timeout for akka ask queries
  val AKKA_TIMEOUT: Timeout = new Timeout(20 seconds)
  //Period for checking timeouts in a chat even if nothing happens
  val CHAT_CHECK_TIMEOUT_PERIOD: Double = 60.0
  //Max length of text in characters
  val MAX_TEXT_LENGTH: Int = 4000
  val MAX_TEXT_LENGTH_MESSAGE: String = "Chat text too long (max " + MAX_TEXT_LENGTH + " chars)"

  //Multiplicative factor by which global chat system limits are larger than individual channel limits
  val CHAT_GLOBAL_BUCKET_FACTOR = 3.0

  //Individual channel limits
  //Allow 20 chat joins in a row, refilling at a rate of 6 per minute
  val CHAT_JOIN_BUCKET_CAPACITY: Double = 20
  val CHAT_JOIN_BUCKET_FILL_PER_SEC: Double = 6.0/60.0
  val CHAT_JOIN_FAIL_MESSAGE: String = "Too many chat joins in a short period, wait a few seconds before attempting to join again."
  //Allow 40 chat messages in a row, refilling at a rate of 10 per minute
  val CHAT_LINE_BUCKET_CAPACITY: Double = 40
  val CHAT_LINE_BUCKET_FILL_PER_SEC: Double = 10.0/60.0
  val CHAT_LINE_FAIL_MESSAGE: String = "Sent too many chat messages in a short period, wait a short while before attempting to chat again."
  //Allow 8000 characters in a row, refilling at a rate of 900 per minute
  val CHAT_CHAR_BUCKET_CAPACITY: Double = 8000
  val CHAT_CHAR_BUCKET_FILL_PER_SEC: Double = 900.0/60.0
  val CHAT_CHAR_FAIL_MESSAGE: String = "Sent too much chat text in a short period, wait a short while before attempting to chat again."

  val NO_CHANNEL_MESSAGE = "No such chat channel, or not authorized"
  val NO_LOGIN_MESSAGE = "Not logged in, or timed out due to inactivity"

  type Channel = String

  val table = TableQuery[ChatTable]
}

import ChatSystem.Channel

sealed trait ChatEvent {
  val name : String
  override def toString: String = name
}

object ChatEvent {
  def ofString(s: String): ChatEvent = {
    s match {
      case "msg" => MSG
      case "join" => JOIN
      case "leave" => LEAVE
      case "timeout" => TIMEOUT
      case _ => throw new Exception("Could not parse string as ChatEvent: " + s)
    }
  }

  case object MSG     extends ChatEvent {val name = "msg"}
  case object JOIN    extends ChatEvent {val name = "join"}
  case object LEAVE   extends ChatEvent {val name = "leave"}
  case object TIMEOUT extends ChatEvent {val name = "timeout"}
}


case class ChatLine(
  id: Long,
  channel: Channel,
  user: SimpleUserInfo,
  timestamp: Timestamp,
  event: ChatEvent,
  label: Option[String],
  text: Option[String])

//---CHAT SYSTEM----------------------------------------------------------------------------------

/** Class representing a whole chat system composed of various channels, backed by a database. */
class ChatSystem(val db: Database, val parentLogins: LoginTracker, val actorSystem: ActorSystem)(implicit ec: ExecutionContext) {

  private var channelData: Map[Channel,ActorRef] = Map()
  implicit val timeout = ChatSystem.AKKA_TIMEOUT

  private val gf = ChatSystem.CHAT_GLOBAL_BUCKET_FACTOR
  private val joinBuckets: TimeBuckets[Username] = new TimeBuckets(ChatSystem.CHAT_JOIN_BUCKET_CAPACITY * gf, ChatSystem.CHAT_JOIN_BUCKET_FILL_PER_SEC * gf)
  private val lineBuckets: TimeBuckets[Username] = new TimeBuckets(ChatSystem.CHAT_LINE_BUCKET_CAPACITY * gf, ChatSystem.CHAT_LINE_BUCKET_FILL_PER_SEC * gf)
  private val charBuckets: TimeBuckets[Username] = new TimeBuckets(ChatSystem.CHAT_CHAR_BUCKET_CAPACITY * gf, ChatSystem.CHAT_CHAR_BUCKET_FILL_PER_SEC * gf)

  val logger =  LoggerFactory.getLogger(getClass)

  private def openChannel(channel: Channel): ActorRef = this.synchronized {
    channelData.get(channel) match {
      case Some(cc) => cc
      case None =>
        val cc = actorSystem.actorOf(Props(new ChatChannel(channel,db,parentLogins,actorSystem,logger,joinBuckets,lineBuckets,charBuckets)))
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
  def join(channel: Channel, user: SimpleUserInfo, siteAuth: SiteAuth): Future[ChatAuth] = {
    val cc = openChannel(channel)
    (cc ? ChatChannel.Join(user,siteAuth)).map(_.asInstanceOf[ChatAuth])
  }

  /** Leave the specified chat channel. Failed if not logged in. */
  def leave(channel: Channel, chatAuth: ChatAuth): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Leave(chatAuth)).map(_.asInstanceOf[Unit])
    }
  }

  /** Post in the specified chat channel. Failed if not logged in. */
  def post(channel: Channel, chatAuth: ChatAuth, text:String): Future[Unit] = {
    if(text.length > ChatSystem.MAX_TEXT_LENGTH)
      Future.failed(new Exception(ChatSystem.MAX_TEXT_LENGTH_MESSAGE))
    else {
      withChannel(channel) { cc =>
        (cc ? ChatChannel.Post(chatAuth,text)).map(_.asInstanceOf[Unit])
      }
    }
  }

  /** Heartbeat the specified chat channel to avoid logout from inactivity. Failed if not logged in. */
  def heartbeat(channel: Channel, chatAuth: ChatAuth): Future[Unit] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.Heartbeat(chatAuth)).map(_.asInstanceOf[Unit])
    }
  }

  /** Gets all users logged in. */
  def usersLoggedIn(channel: Channel): Future[List[SimpleUserInfo]] = {
    withChannel(channel) { cc =>
      (cc ? ChatChannel.UsersLoggedIn()).map(_.asInstanceOf[List[SimpleUserInfo]])
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
  case class Join(user: SimpleUserInfo, siteAuth: SiteAuth)
  //Replies with Unit
  case class Leave(chatAuth: ChatAuth)
  //Replies with Unit
  case class Post(chatAuth: ChatAuth, text:String)
  //Replies with Unit
  case class Heartbeat(chatAuth: ChatAuth)

  //Replies with List[SimpleUserInfo]
  case class UsersLoggedIn()

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
class ChatChannel(
  val channel: Channel,
  val db: Database,
  val parentLogins: LoginTracker,
  val actorSystem: ActorSystem,
  val logger: Logger,
  val globalJoinBuckets: TimeBuckets[Username],
  val globalLineBuckets: TimeBuckets[Username],
  val globalCharBuckets: TimeBuckets[Username]
) extends Actor with Stash {

  //Fulfilled and replaced on each message - this is the mechanism by which
  //queries can block and wait for chat activity
  var nextMessage: Promise[ChatLine] = Promise()
  var nextId: Long = 0L

  //Holds messages that we are not confident are in the database yet, avoiding
  //races between writes to the chat and queries to read the new lines posted
  var messagesNotYetInDB: Queue[ChatLine] = Queue()

  //Tracks who is logged in to this chat channel
  val logins: LoginTracker = new LoginTracker(Some(parentLogins), ChatSystem.INACTIVITY_TIMEOUT, ChatSystem.INACTIVITY_TIMEOUT, ChatSystem.INACTIVITY_TIMEOUT, updateInfosFromParent = true)
  //Most recent time anything happened in this channel
  var lastActive = Timestamp.get

  //Whether or not we started the loop that checks timeouts for the chat
  var timeoutCycleStarted = false

  private val joinBuckets: TimeBuckets[Username] = new TimeBuckets(ChatSystem.CHAT_JOIN_BUCKET_CAPACITY, ChatSystem.CHAT_JOIN_BUCKET_FILL_PER_SEC)
  private val lineBuckets: TimeBuckets[Username] = new TimeBuckets(ChatSystem.CHAT_LINE_BUCKET_CAPACITY, ChatSystem.CHAT_LINE_BUCKET_FILL_PER_SEC)
  private val charBuckets: TimeBuckets[Username] = new TimeBuckets(ChatSystem.CHAT_CHAR_BUCKET_CAPACITY, ChatSystem.CHAT_CHAR_BUCKET_FILL_PER_SEC)

  case class Initialized(maxId: Try[Long])
  case class DBWritten(upToId: Long)
  case class DoTimeouts()

  implicit val timeout = ChatSystem.AKKA_TIMEOUT
  import context.dispatcher

  override def preStart : Unit = {
    //Find the maximum chat id in this channel from the database
    val query: Rep[Option[Long]] = ChatSystem.table.filter(_.channel === channel).map(_.id).max
    db.run(query.result).map(_.getOrElse(-1L)).onComplete {
      result => self ! Initialized(result)
    }
    if(!timeoutCycleStarted) {
      timeoutCycleStarted = true
      actorSystem.scheduler.scheduleOnce(ChatSystem.CHAT_CHECK_TIMEOUT_PERIOD seconds, self, DoTimeouts())
      ()
    }
  }

  def receive : Receive = initialReceive
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
    case ChatChannel.Join(user: SimpleUserInfo, siteAuth: SiteAuth) =>
      val result: Try[ChatAuth] = Try {
        val now = Timestamp.get
        if(!joinBuckets.takeOne(user.name,now))
          throw new Exception(ChatSystem.CHAT_JOIN_FAIL_MESSAGE)
        else if(!globalJoinBuckets.takeOne(user.name,now)) {
          joinBuckets.putOne(user.name,now)
          throw new Exception(ChatSystem.CHAT_JOIN_FAIL_MESSAGE)
        }
        else {
          logins.doTimeouts(now)
          val chatAuth = logins.login(user, now, Some(siteAuth))
          lastActive = now
          chatAuth
        }
      }
      replyWith(sender, result)

    case ChatChannel.Leave(chatAuth: ChatAuth) =>
      val result: Try[Unit] = requiringLogin(chatAuth) { user =>
        logins.logout(user.name,chatAuth,Timestamp.get)
      }
      replyWith(sender, result)

    case ChatChannel.Post(chatAuth: ChatAuth, text:String) =>
      val result: Try[Unit] = requiringLogin(chatAuth) { user =>
        val now = Timestamp.get

        if(text.length > ChatSystem.MAX_TEXT_LENGTH)
          throw new Exception(ChatSystem.MAX_TEXT_LENGTH_MESSAGE)

        if(!lineBuckets.takeOne(user.name,now))
          throw new Exception(ChatSystem.CHAT_LINE_FAIL_MESSAGE)
        else if(!charBuckets.take(user.name, text.length.toDouble, now)) {
          lineBuckets.putOne(user.name,now)
          throw new Exception(ChatSystem.CHAT_CHAR_FAIL_MESSAGE)
        }
        else if(!globalLineBuckets.takeOne(user.name,now)) {
          lineBuckets.putOne(user.name,now)
          charBuckets.put(user.name, text.length.toDouble, now)
          throw new Exception(ChatSystem.CHAT_LINE_FAIL_MESSAGE)
        }
        else if(!globalCharBuckets.take(user.name, text.length.toDouble, now)) {
          lineBuckets.putOne(user.name,now)
          charBuckets.put(user.name, text.length.toDouble, now)
          globalLineBuckets.putOne(user.name,now)
          throw new Exception(ChatSystem.CHAT_CHAR_FAIL_MESSAGE)
        }

        val line = ChatLine(nextId, channel, user, now, ChatEvent.MSG, None, Some(text))
        nextId = nextId + 1

        //Add to queue of lines that we will remember until they show up in the db
        messagesNotYetInDB = messagesNotYetInDB.enqueue(line)

        //Write to DB and on success clear own memory
        val query = ChatSystem.table += line
        db.run(DBIO.seq(query)).onComplete {
          case Failure(err) => logger.error("Error saving chat post: " + err.getMessage)
          case Success(()) => self ! DBWritten(line.id)
        }

        nextMessage.success(line)
        nextMessage = Promise()
      }
      replyWith(sender, result)

    case ChatChannel.Heartbeat(chatAuth: ChatAuth) =>
      val result: Try[Unit] = requiringLogin(chatAuth) { (_ : SimpleUserInfo) => () }
      replyWith(sender, result)

    case ChatChannel.UsersLoggedIn() =>
      val result = logins.usersLoggedIn
      sender ! (result : List[SimpleUserInfo])


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
          val timeout = akka.pattern.after(ChatSystem.GET_TIMEOUT seconds, actorSystem.scheduler)(Future.successful(List()))
          Future.firstCompletedOf(List(result,timeout))
        }
        else {
          Future.successful(lines)
        }
      }
      result pipeTo sender
      ()

    case DBWritten(upToId: Long) =>
      messagesNotYetInDB = messagesNotYetInDB.dropWhile { line =>
        line.id <= upToId
      }

    case DoTimeouts() =>
      val now = Timestamp.get
      //TODO if nobody is logged in for long enough, then shut down this chat!
      logins.doTimeouts(now)
      actorSystem.scheduler.scheduleOnce(ChatSystem.CHAT_CHECK_TIMEOUT_PERIOD seconds, self, DoTimeouts())
      ()
  }

  def replyWith[T](sender: ActorRef, result: Try[T]) : Unit = {
    result match {
      case Failure(e) => sender ! akka.actor.Status.Failure(e)
      case Success(x) => sender ! x
    }
  }

  def requiringLogin[T](chatAuth: ChatAuth)(f:SimpleUserInfo => T) : Try[T] = {
    val now = Timestamp.get
    logins.doTimeouts(now)
    logins.heartbeatAuth(chatAuth,now) match {
      case None => Failure(new Exception(ChatSystem.NO_LOGIN_MESSAGE))
      case Some(user) =>
        lastActive = now
        Try(f(user))
    }
  }

}

class ChatTable(tag: Tag) extends Table[ChatLine](tag, "chatTable") {
  def id : Rep[Long] = column[Long]("id")
  def channel : Rep[String] = column[String]("channel")
  def username : Rep[Username] = column[Username]("username")
  def rating : Rep[Double] = column[Double]("rating")
  def ratingStdev : Rep[Double] = column[Double]("ratingStdev")
  def isBot : Rep[Boolean] = column[Boolean]("isBot")
  def isGuest : Rep[Boolean] = column[Boolean]("isGuest")
  def userID : Rep[String] = column[String]("userID")
  def timestamp : Rep[Double] = column[Double]("time")
  def event : Rep[ChatEvent] = column[ChatEvent]("event")
  def label : Rep[Option[String]] = column[Option[String]]("label")
  def text : Rep[Option[String]] = column[Option[String]]("text")

  implicit val chatEventMapper = MappedColumnType.base[ChatEvent, String] (
    { chatEvent => chatEvent.toString },
    { str => ChatEvent.ofString(str) }
  )

  //The * projection (e.g. select * ...) auto-transforms the tuple to the case class
  def * : ProvenShape[ChatLine] = (
    id, channel,
    (username,rating,ratingStdev,isBot,isGuest,userID),
    timestamp, event, label, text
  ).shaped <> (
    //Database shape -> Scala object
    { case (id, channel,
      userInfo,
      timestamp, event, label, text) =>
      ChatLine(id,channel,SimpleUserInfo.ofDB(userInfo),timestamp,event,label,text)
    },
    //Scala object -> Database shape
    { c: ChatLine =>
      Some((c.id, c.channel,
        SimpleUserInfo.toDB(c.user),
        c.timestamp, c.event, c.label, c.text))
    }
  )
}
