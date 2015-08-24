package org.playarimaa.server.chat
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt}
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.slf4j.{Logger, LoggerFactory}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import slick.driver.H2Driver.api.Database

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.{Accounts,Json,LoginTracker,SiteLogin,Timestamp,WebAppStack}
import org.playarimaa.server.Timestamp.Timestamp
import org.playarimaa.server.game.Games

object ChatServlet {

  object IOTypes {
    case class SimpleError(error: String)
    case class ChatLine(id: Long, channel: String, username: String, text: String, timestamp: Double)
  }

  sealed trait Action {
    val name: String
    abstract class Query
    abstract class Reply
  }
  object Action {
    val all: List[Action] = List(Join,Leave,Post)
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
  case object Post extends Action {
    val name = "post"
    case class Query(chatAuth: String, text: String)
    case class Reply(message: String)
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

}

import org.playarimaa.server.chat.ChatServlet._

class ChatServlet(val accounts: Accounts, val siteLogin: SiteLogin, val chat: ChatSystem, val games: Games, val ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = Json.formats
  protected implicit def executor: ExecutionContext = ec

  val logger =  LoggerFactory.getLogger(getClass)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
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
            case Post =>
              val query = Json.read[Post.Query](requestBody)
              chat.post(channel, query.chatAuth, query.text).map { case () =>
                Post.Reply("Ok")
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
      chat.get(channel, query.minId, None, query.minTime, None, query.doWait).map { chatLines =>
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

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
