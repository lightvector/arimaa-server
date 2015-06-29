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

object Chat {
  //Leave chat if it's been 120 seconds with no activity
  val INACTIVITY_TIMEOUT: Double = 120
  //Max lines at a time to read and return
  val READ_MAX_LINES: Int = 5000
  //Timeout for waiting for chat messages
  val GET_TIMEOUT: Double = 15
  //Timeout for akka ask queries
  val AKKA_TIMEOUT: Timeout = new Timeout(20 seconds)

  val NO_LOGIN_MESSAGE = "Not logged in, or timed out due to inactivity"

  object Import {
    type Channel = String
    type Username = String
    type Auth = String
  }
}

import Chat.Import._

class Chat(val db: Database, val actorSystem: ActorSystem)(implicit ec: ExecutionContext) {

  private var channelData: Map[Channel,ActorRef] = Map()
  private val chatDB = actorSystem.actorOf(Props(new ChatDB(db)))
  implicit val timeout = Chat.AKKA_TIMEOUT

  private def openChannel(channel: String): ActorRef = this.synchronized {
    channelData.get(channel) match {
      case Some(cc) => cc
      case None =>
        val cc = actorSystem.actorOf(Props(new ChatChannel(channel,chatDB,actorSystem)))
        channelData = channelData + (channel -> cc)
        cc
    }
  }

  def join(channel: Channel, username: Username): Future[Auth] = {
    val cc = openChannel(channel)
    (cc ? ChatChannel.Join(username)).map(_.asInstanceOf[Auth])
  }

  private def requiringLogin[T](channel: String)(f:ActorRef => Future[T]) : Future[T] = {
    val cc = this.synchronized { channelData.get(channel) }
    cc match {
      case None => Future.failed(new Exception(Chat.NO_LOGIN_MESSAGE))
      case Some(cc) => f(cc)
    }
  }

  def leave(channel: String, username: Username, auth: Auth): Future[Unit] = {
    requiringLogin(channel) { cc =>
      (cc ? ChatChannel.Leave(username,auth)).map(_.asInstanceOf[Unit])
    }
  }

  def post(channel: String, username: Username, auth: String, text:String): Future[Unit] = {
    requiringLogin(channel) { cc =>
      (cc ? ChatChannel.Post(username,auth,text)).map(_.asInstanceOf[Unit])
    }
  }

  def heartbeat(channel: String, username: Username, auth: String): Future[Unit] = {
    requiringLogin(channel) { cc =>
      (cc ? ChatChannel.Heartbeat(username,auth)).map(_.asInstanceOf[Unit])
    }
  }

  /** [minId] defaults to the current end of chat minus [READ_MAX_LINES]
    * [doWait] defaults to false.
    * All other optional parameters default to having no effect.
    */
  def get(channel: String, minId: Option[Long], maxId: Option[Long], minTime: Option[Timestamp], maxTime: Option[Timestamp], doWait: Option[Boolean])
      : Future[List[ChatLine]] = {
    val cc = openChannel(channel)
    val maxId_ = maxId.getOrElse(Chat.READ_MAX_LINES + 1000000L)
    val minTime_ = minTime.getOrElse(0.0)
    val maxTime_ = maxTime.getOrElse(1e60)
    val doWait_ = doWait.getOrElse(false)
    (cc ? ChatChannel.Get(minId,maxId_,minTime_,maxTime_,doWait_)).map(_.asInstanceOf[List[ChatLine]])
  }
}

object ChatChannel {

  //ACTOR MESSAGES---------------------------------------------------------

  //Replies with Auth
  case class Join(username: String)
  //Replies with Unit
  case class Leave(username: String, auth: Auth)
  //Replies with Unit
  case class Post(username: String, auth: Auth, text:String)
  //Replies with Unit
  case class Heartbeat(username: String, auth: Auth)

  /** [minId] defaults to the current end of chat minus [READ_MAX_LINES]
    * Replies with List[ChatLine]
    */
  case class Get(minId: Option[Long], maxId: Long, minTime: Timestamp, maxTime: Timestamp, doWait: Boolean)
}

class ChatChannel(val channel: Channel, val chatDB: ActorRef, val actorSystem: ActorSystem) extends Actor with Stash {

  class LoginData() {
    var auths: Map[Auth,Timestamp] = Map()
    var lastActive: Timestamp = Timestamp.get
  }
  var loginData: Map[Username,LoginData] = Map()
  var nextId: Long = 0L
  var nextMessage: Promise[ChatLine] = Promise()
  var lastActive: Timestamp = Timestamp.get

  var messagesNotYetInDB: Queue[ChatLine] = Queue()

  case class Initialized(nextId:Try[Long])
  case class DBWritten(upToId:Long)

  implicit val timeout = Chat.AKKA_TIMEOUT
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
      updateLastActive
      val auth = AuthTokenGen.genToken
      val ld = findOrAddLogin(username)
      ld.auths = ld.auths + (auth -> lastActive)
      sender ! (auth : Auth)

    case ChatChannel.Leave(username: Username, auth: Auth) =>
      val result: Try[Unit] = requiringLogin(username,auth) { () =>
        val ld = loginData(username)
        ld.auths = ld.auths - auth
      }
      replyWith(sender, result)

    case ChatChannel.Post(username: Username, auth: String, text:String) =>
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


    case ChatChannel.Get(minId: Option[Long], maxId: Long, minTime: Timestamp, maxTime: Timestamp, doWait: Boolean) =>
      val minId_ = minId.getOrElse(nextId - Chat.READ_MAX_LINES)

      updateLastActive
      def isOk(x:ChatLine): Boolean = {
        x.id >= minId_ &&
        x.id <= maxId &&
        x.timestamp >= minTime &&
        x.timestamp <= maxTime
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

        if(doWait && lines.isEmpty) {
          val result = nextMessage.future.map { x => List(x).filter(isOk) }
          val timeout = akka.pattern.after(Chat.GET_TIMEOUT seconds, actorSystem.scheduler)(Future(List()))
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

  def updateLastActive: Unit =
    lastActive = Timestamp.get

  def findOrAddLogin(username: Username): LoginData = {
    val ld = loginData.getOrElse(username, new LoginData)
    loginData = loginData + (username -> ld)
    ld
  }

  def requiringLogin[T](username: Username, auth: Auth)(f:() => T) : Try[T] = {
    loginData.get(username) match {
      case None => Failure(new Exception(Chat.NO_LOGIN_MESSAGE))
      case Some(ld) =>
        ld.auths.get(auth) match {
          case None => Failure(new Exception(Chat.NO_LOGIN_MESSAGE))
          case Some(time) =>
            val now = Timestamp.get
            if(now >= time + Chat.INACTIVITY_TIMEOUT)
              Failure(new Exception(Chat.NO_LOGIN_MESSAGE))
            else {
              ld.auths = ld.auths + (auth -> now)
              updateLastActive
              Success(f())
            }
        }
    }
  }

  def clearMessagesNotYetInDB(upToId: Long) {
    messagesNotYetInDB = messagesNotYetInDB.dropWhile{ line =>
      line.id <= upToId
    }
  }


}




case class ChatLine(id: Long, channel: String, username: String, text: String, timestamp: Timestamp)

object ChatDB {

  //ACTOR MESSAGES---------------------------------------------------------

  //Replies with Long
  case class GetNextId(channel: String)
  //Replies with Unit when result committed
  case class WriteLine(line: ChatLine)
  //Replies with List[ChatLine]
  case class ReadLines(channel: String, minId: Long, maxId: Long, minTime: Timestamp, maxTime: Timestamp)


  val table = TableQuery[ChatTable]
}

class ChatDB(val db: Database)(implicit ec: ExecutionContext) extends Actor {

  def receive = {
    case ChatDB.GetNextId(channel) => {
      val query: Rep[Option[Long]] = ChatDB.table.filter(_.channel === channel).map(_.id).max
      //println("Generated SQL:\n" + query.result.statements)
      val result: Future[Long] = db.run(query.result).map(_.getOrElse(-1L))
      result pipeTo sender
    }

    case ChatDB.WriteLine(line) => {
      val query = ChatDB.table += line
      val result: Future[Unit] = db.run(DBIO.seq(query))
      result pipeTo sender
    }

    case ChatDB.ReadLines(channel, minId, maxId, minTime, maxTime) => {
      val query: Rep[Seq[ChatLine]] = ChatDB.table.
        filter(_.channel === channel).
        filter(_.id >= minId).
        filter(_.id <= maxId).
        filter(_.timestamp >= minTime).
        filter(_.timestamp <= maxTime).
        sortBy(_.id).
        take(Chat.READ_MAX_LINES)
      val result: Future[List[ChatLine]] = db.run(query.result).map(_.toList)
      result pipeTo sender
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
