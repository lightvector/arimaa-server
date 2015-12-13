package org.playarimaa.server.chat
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, SessionSupport, ScalatraServlet}
import org.scalatra.atmosphere.{AtmosphereSupport,AtmosphereClient}
import org.scalatra.{atmosphere => Atmosphere}
import org.scalatra.scalate.ScalateSupport

import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt,DurationDouble}
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.slf4j.{Logger, LoggerFactory}
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Scheduler}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import slick.driver.H2Driver.api.Database

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.{Accounts,Json,LoginTracker,SimpleUserInfo,SiteLogin,Timestamp,WebAppStack}
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.game.Games

object ChatServlet {
  object IOTypes {
    case class SimpleError(error: String)
    case class ChatLine(id: Long, channel: String, username: String, text: String, timestamp: Double)

    case class ShortUserInfo(
      name: String,
      rating: Double,
      isBot: Boolean,
      isGuest: Boolean
    )
  }

  sealed trait Action {
    val name: String
    abstract class Query
    abstract class Reply
  }
  object Action {
    val all: List[Action] = List(Join,Leave,Heartbeat,Post,UsersLoggedIn)
  }

  case object Join extends Action {
    val name = "join"
    case class Query(siteAuth: String)
    case class Reply(chatAuth: String)
  }
  case object Leave extends Action {
    val name = "leave"
    case class Query(chatAuth: String)
    case class Reply(message: String)
  }
  case object Heartbeat extends Action {
    val name = "heartbeat"
    case class Query(chatAuth: String)
    case class Reply(message: String)
  }
  case object Post extends Action {
    val name = "post"
    case class Query(chatAuth: String, text: String)
    case class Reply(message: String)
  }
  case object UsersLoggedIn extends Action {
    val name = "usersLoggedIn"
    case class Query()
    case class Reply(users: List[IOTypes.ShortUserInfo])
  }

  case object Get {
    case class Query(minId: Option[Long], minTime: Option[Double], doWait: Option[Boolean])
    case class Reply(lines: List[IOTypes.ChatLine])

    def parseQuery(params: Map[String,String]): Query =
      new Query(
        params.get("minId").map(_.toLong),
        params.get("minTime").map(_.toDouble),
        params.get("doWait").map(_.toBoolean)
      )
  }

  case object SocketMessage {
    case class Query(action: String, text: String)
  }
}

import org.playarimaa.server.chat.ChatServlet._

class ChatServlet(val accounts: Accounts, val siteLogin: SiteLogin, val chat: ChatSystem, val games: Games, val scheduler: Scheduler, val ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport with SessionSupport with AtmosphereSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = Json.formats
  protected implicit def executor: ExecutionContext = ec

  val logger =  LoggerFactory.getLogger(getClass)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  def convUser(user: SimpleUserInfo): IOTypes.ShortUserInfo = {
    IOTypes.ShortUserInfo(user.name,user.rating,user.isBot,user.isGuest)
  }

  def getAction(action: String): Option[Action] = {
    Action.all.find(_.name == action)
  }

  def handleAction(channel: String, params: Map[String,String], requestBody: String, isValidChannel: => Future[Boolean]) : AnyRef = {
    getAction(params("action")) match {
      case None =>
        pass()
      case Some(action) =>
        isValidChannel.flatMap { isValid =>
          if(!isValid)
            throw new Exception("Unknown chat channel: " + channel)
          action match {
            case Join =>
              val query = Json.read[Join.Query](requestBody)
              siteLogin.requiringLogin(query.siteAuth) { username =>
                chat.join(channel, username, query.siteAuth).map { chatAuth =>
                  Join.Reply(chatAuth)
                }
              }.get
            case Leave =>
              val query = Json.read[Leave.Query](requestBody)
              chat.leave(channel, query.chatAuth).map { case () =>
                Leave.Reply("Ok")
              }
            case Heartbeat =>
              val query = Json.read[Heartbeat.Query](requestBody)
              chat.heartbeat(channel, query.chatAuth).map { case () =>
                Heartbeat.Reply("Ok")
              }
            case Post =>
              val query = Json.read[Post.Query](requestBody)
              chat.post(channel, query.chatAuth, query.text).map { case () =>
                Post.Reply("Ok")
              }
            case UsersLoggedIn =>
              val query = Json.read[UsersLoggedIn.Query](requestBody)
              chat.usersLoggedIn(channel).map { users =>
                UsersLoggedIn.Reply(users.map(convUser(_)))
              }
          }
        }
    }
  }

  def handleGet(channel: String, params: Map[String,String], isValidChannel: => Future[Boolean]) : AnyRef = {
    val query = Get.parseQuery(params)
    isValidChannel.flatMap { isValid =>
      if(!isValid)
        throw new Exception("Unknown chat channel: " + channel)
      chat.get(
        channel = channel,
        minId = query.minId,
        maxId = None,
        minTime = query.minTime,
        maxTime = None,
        doWait = query.doWait
      ).map { chatLines =>
        Get.Reply(chatLines.map { line =>
          IOTypes.ChatLine(
            id = line.id,
            channel = line.channel,
            username = line.username,
            text = line.text,
            timestamp = line.timestamp
          )
        })
      }
    }
  }

  //TODO this could be cleaned up slightly
  def handleAtmosphere(channel: String, params: Map[String,String], isValidChannel: => Future[Boolean]) : AtmosphereClient = {
    var connected = false
    var chatAuth : Option[ChatAuth] = None
    new AtmosphereClient {
      def sendError(msg: String) =
        send(Json.write(IOTypes.SimpleError(msg)))

      def receive = {
        case Atmosphere.Connected =>
          var minId : Option[Long] = None
          //TODO: this is a bit inefficient, we should consider broadcasting instead. Although, there are some tricky
          //invloving the initial get versus any new messages
          //Loop to report chat lines to this connection
          def loop(): Unit = {
            if(connected) {
              //Heartbeat if applicable, but don't wait on it
              chatAuth.foreach { chatAuth =>
                chat.heartbeat(channel,chatAuth)
                ()
              }
              //Loop getting stuff
              isValidChannel.flatMap { isValid =>
                if(!isValid)
                  throw new Exception("Unknown chat channel: " + channel)
                chat.get(
                  channel = channel,
                  minId = minId,
                  maxId = None,
                  minTime = None,
                  maxTime = None,
                  doWait = Some(true)
                )
              }.onComplete { result =>
                //If still connected after getting chat messages...
                if(connected) {
                  result match {
                    case Failure(e) => sendError(e.getMessage())
                    case Success(lines) =>
                      lines.foreach { line =>
                        if(minId.forall(_ <= line.id))
                          minId = Some(line.id+1)

                        val output = IOTypes.ChatLine(
                          id = line.id,
                          channel = line.channel,
                          username = line.username,
                          text = line.text,
                          timestamp = line.timestamp
                        )
                        send(Json.write(output))
                      }
                  }
                  loop()
                }
              }
            }
          }
          connected = true
          loop()

        case Atmosphere.Disconnected(disconnector, None) =>
          connected = false
          chatAuth.foreach { chatAuth => chat.leave(channel,chatAuth) }
          logger.info("chat channel " + channel + ": " + disconnector + " disconnected")
        case Atmosphere.Disconnected(disconnector, Some(error)) =>
          connected = false
          chatAuth.foreach { chatAuth => chat.leave(channel,chatAuth) }
          logger.error("chat channel " + channel + ": " + disconnector + " disconnected due to error " + error)
        case Atmosphere.Error(None) =>
          logger.error("chat channel " + channel + ": unknown error event")
        case Atmosphere.Error(Some(error)) =>
          logger.error("chat channel " + channel + ": " + error)
        case Atmosphere.TextMessage(_) =>
          sendError("Unable to parse message")
          ()
        case Atmosphere.JsonMessage(json) =>
          val msg = Try(Json.extract[SocketMessage.Query](json))
          msg match {
            case Failure(e) => sendError(e.getMessage())
            case Success(msg) =>
              msg.action match {
                case "join" =>
                  val siteAuth = msg.text
                  siteLogin.requiringLogin(siteAuth) { username =>
                    chat.join(channel,username,siteAuth).onComplete { result =>
                      if(connected) {
                        result match {
                          case Failure(e) => sendError(e.getMessage())
                          case Success(cAuth) =>
                            chatAuth.foreach { chatAuth => chat.leave(channel,chatAuth); () }
                            chatAuth = Some(cAuth)
                            send(Json.write(Join.Reply(cAuth)))
                        }
                      }
                    }
                  } match {
                    case Failure(e) => sendError(e.getMessage())
                    case Success(()) => ()
                  }
                case "post" =>
                  chatAuth match {
                    case None =>
                      sendError("Not logged in")
                    case Some(chatAuth) =>
                      chat.post(channel, chatAuth, msg.text).onComplete { result =>
                        if(connected) {
                          result match {
                            case Failure(e) => sendError(e.getMessage())
                            case Success(()) => ()
                          }
                        }
                      }
                  }
                case _ =>
                  sendError("Unknown action: " + msg.action)
              }
          }
      }
    }
  }


  def isValidBaseChannel(channel: String): Boolean = {
    channel == "main"
  }
  def isValidGameID(gameID: GameID): Future[Boolean] = {
    games.gameExists(gameID)
  }

  get("/:channel") {
    val channel = params("channel")
    def isValidChannel: Future[Boolean] = Future.successful(isValidBaseChannel(channel))
    handleGet(channel,params,isValidChannel)
  }
  post("/:channel/:action") {
    val channel = params("channel")
    def isValidChannel: Future[Boolean] = Future.successful(isValidBaseChannel(channel))
    handleAction(channel,params,request.body,isValidChannel)
  }
  atmosphere("/:channel/socket") {
    val channel = params("channel")
    def isValidChannel: Future[Boolean] = Future.successful(isValidBaseChannel(channel))
    handleAtmosphere(channel,params,isValidChannel)
  }

  get("/game/:gameID") {
    val gameID = params("gameID")
    def isValidChannel: Future[Boolean] =
      isValidGameID(gameID).map {
        case false => throw new Exception("Game " + gameID + "does not exist.")
        case true => true
      }
    val channel = "game/" + gameID
    handleGet(channel,params,isValidChannel)
  }
  post("/game/:gameID/:action") {
    val gameID = params("gameID")
    def isValidChannel: Future[Boolean] =
      isValidGameID(gameID).map {
        case false => throw new Exception("Game " + gameID + "does not exist.")
        case true => true
      }
    val channel = "game/" + gameID
    handleAction(channel,params,request.body,isValidChannel)
  }
  atmosphere("/game/:gameID/socket") {
    val gameID = params("gameID")
    def isValidChannel: Future[Boolean] =
      isValidGameID(gameID).map {
        case false => throw new Exception("Game " + gameID + "does not exist.")
        case true => true
      }
    val channel = "game/" + gameID
    handleAtmosphere(channel,params,isValidChannel)
  }

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
