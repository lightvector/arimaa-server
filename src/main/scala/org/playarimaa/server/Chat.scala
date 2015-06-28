package org.playarimaa.server
import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
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
  val AKKA_TIMEOUT: Timeout = new Timeout(600 seconds)
}

class Chat(val db: Database, val actorSystem: ActorSystem)(implicit ec: ExecutionContext) {

  type Channel = String
  type Username = String
  type Auth = String
  class LoginData() {
    var auths: Map[Auth,Timestamp] = Map()
    var lastActive: Timestamp = Timestamp.get
  }
  class ChannelData(initNextId: Long) {
    var loginData: Map[Username,LoginData] = Map()
    var nextId: Long = initNextId
    var nextMessage: Promise[ChatLine] = Promise()
    var lastActive: Timestamp = Timestamp.get

    def touch: Unit =
      lastActive = Timestamp.get

    def findOrAddLogin(username: Username): LoginData = {
      val ld = loginData.getOrElse(username, new LoginData)
      loginData = loginData + (username -> ld)
      ld
    }
  }

  private var channelData: Map[Channel,ChannelData] = Map()
  private val chatDB = actorSystem.actorOf(Props(new ChatDB(db)))

  private def openChannel(channel: String)(implicit ec: ExecutionContext): Future[ChannelData] = this.synchronized {
    implicit val timeout = Chat.AKKA_TIMEOUT
    channelData.get(channel) match {
      case Some(cd) => cd.touch; Future(cd)
      case None => (chatDB ? ChatDB.GetNextId(channel)) map { result =>
        val nextId = 1L + result.asInstanceOf[Long]
        this.synchronized {
          channelData.get(channel) match {
            case Some(cd) => cd.touch; cd
            case None =>
              val cd = new ChannelData(nextId)
              channelData = channelData + (channel -> cd)
              cd
            }
          }
      }
    }
  }

  def join(channel: Channel, username: Username)(implicit ec: ExecutionContext): Future[Auth] = {
    openChannel(channel).map { cd => this.synchronized {
      val auth = AuthTokenGen.genToken
      val ld = cd.findOrAddLogin(username)
      cd.touch
      ld.auths = ld.auths + (auth -> cd.lastActive)
      auth
    }}
  }

  private def requiringLogin[T](channel: String, username: Username, auth: Auth)(f:ChannelData => T) : Try[T] = {
    channelData.get(channel) match {
      case None => Failure(new Exception("Not logged in"))
      case Some(cd) =>
        cd.loginData.get(username) match {
          case None => Failure(new Exception("Not logged in"))
          case Some(ld) =>
            ld.auths.get(auth) match {
              case None => Failure(new Exception("Not logged in"))
              case Some(time) =>
                val now = Timestamp.get
                if(now >= time + Chat.INACTIVITY_TIMEOUT)
                  Failure(new Exception("Disconnected due to timeout"))
                else {
                  ld.auths = ld.auths + (auth -> now)
                  cd.touch
                  Success(f(cd))
                }
            }
        }
    }
  }

  def leave(channel: String, username: Username, auth: Auth): Try[Unit] = this.synchronized {
    requiringLogin(channel,username,auth) { cd =>
      val ld = cd.loginData(username)
      ld.auths = ld.auths - auth
    }
  }

  def post(channel: String, username: Username, auth: String, text:String): Try[Unit] = this.synchronized {
    requiringLogin(channel,username,auth) { cd =>
      val line = ChatLine(cd.nextId, channel, username, text, Timestamp.get)
      cd.nextId = cd.nextId + 1
      chatDB ! ChatDB.WriteLine(line)
      cd.nextMessage.success(line)
      cd.nextMessage = Promise()
    }
  }

  def heartbeat(channel: String, username: Username, auth: String): Try[Unit] = this.synchronized {
    requiringLogin(channel,username,auth) { _ => ()}
  }

  /** [minId] defaults to the current end of chat minus [READ_MAX_LINES]
    * All other optional parameters default to having no effect.
    */
  def get(channel: String, minId: Option[Long], maxId: Option[Long], minTime: Option[Timestamp], maxTime: Option[Timestamp], doWait: Boolean)
    (implicit ec: ExecutionContext): Future[List[ChatLine]] =
    openChannel(channel).flatMap { cd => this.synchronized {
      val minId_ = minId.getOrElse(cd.nextId - Chat.READ_MAX_LINES)
      val maxId_ = maxId.getOrElse(Chat.READ_MAX_LINES + 1000000L)
      val minTime_ = minTime.getOrElse(0.0)
      val maxTime_ = maxTime.getOrElse(cd.lastActive + 1000000.0)

      val query = ChatDB.ReadLines(channel,minId_,maxId_,minTime_,maxTime_)
      implicit val timeout = Chat.AKKA_TIMEOUT
      (chatDB ? query).flatMap { result =>
        val lines = result.asInstanceOf[List[ChatLine]]
        if(doWait && lines.isEmpty) {
          val result = cd.nextMessage.future.map { x =>
            if(x.id >= minId_ &&
              x.id <= maxId_ &&
              x.timestamp >= minTime_ &&
              x.timestamp <= maxTime_
            )
              List()
            else
              List(x)

          }
          val timeout = akka.pattern.after(Chat.GET_TIMEOUT seconds, actorSystem.scheduler)(Future(List()))
          Future.firstCompletedOf(List(result,timeout))
        }
        else {
          Future(lines)
        }
      }
    }}
}


case class ChatLine(id: Long, channel: String, username: String, text: String, timestamp: Timestamp)

object ChatDB {
  case class GetNextId(channel: String)
  case class WriteLine(line: ChatLine)
  case class ReadLines(channel: String, minId: Long, maxId: Long, minTime: Timestamp, maxTime: Timestamp)
}

class ChatDB(val db: Database)(implicit ec: ExecutionContext) extends Actor {

  val table = TableQuery[ChatTable]

  def receive = {
    case ChatDB.GetNextId(channel) => {
      val origSender = sender
      val query: Rep[Option[Long]] = table.filter(_.channel === channel).map(_.id).max
      //println("Generated SQL:\n" + query.result.statements)
      db.run(query.result).foreach { result =>
        origSender ! result.getOrElse(0L)
      }
    }
    case ChatDB.WriteLine(line) => {
      val query = table += line
      db.run(query)
    }
    case ChatDB.ReadLines(channel, minId, maxId, minTime, maxTime) => {
      val origSender = sender
      val query = table.
        filter(_.channel === channel).
        filter(_.id >= minId).
        filter(_.id <= maxId).
        filter(_.timestamp >= minTime).
        filter(_.timestamp <= maxTime).
        sortBy(_.id).
        take(Chat.READ_MAX_LINES)
      db.run(query.result).foreach { result =>
        origSender ! result
      }
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
