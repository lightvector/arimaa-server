package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt}
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import slick.driver.H2Driver.api.Database

import org.playarimaa.server.CommonTypes._
import org.playarimaa.server.Timestamp.Timestamp

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
    case class Query(username: String)
    case class Reply(username: String, auth: String)
  }
  case object Leave extends Action {
    val name = "leave"
    case class Query(username: String, auth: String)
    case class Reply(message: String)
  }
  case object Post extends Action {
    val name = "post"
    case class Query(username: String, auth: String, text: String)
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

import org.playarimaa.server.ChatServlet._

class ChatServlet(system: ActorSystem, db: Database, ec: ExecutionContext)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = Json.formats
  protected implicit def executor: ExecutionContext = ec

  val chat = new ChatSystem(db,system)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  def getAction(action: String): Option[Action] = {
    Action.all.find(_.name == action)
  }

  def handleAction(channel: String, params: Map[String,String]) : AnyRef = {
    getAction(params("action")) match {
      case None =>
        pass()
      case Some(Join) =>
        val query = Json.read[Join.Query](request.body)
        chat.join(channel, query.username).map { auth =>
          Join.Reply(query.username,auth)
        }
      case Some(Leave) =>
        val query = Json.read[Leave.Query](request.body)
        chat.leave(channel, query.auth).map { case () =>
          Leave.Reply("Ok")
        }
      case Some(Post) =>
        val query = Json.read[Post.Query](request.body)
        chat.post(channel, query.auth, query.text).map { case () =>
          Post.Reply("Ok")
        }
    }
  }

  def handleGet(channel: String, params: Map[String,String]) : AnyRef = {
    val query = Get.parseQuery(params)
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

  def isValidBaseChannel(channel: String): Boolean = {
    channel == "main"
  }
  def isValidGameId(gameId: String): Boolean = {
    //TODO
    false
  }

  get("/:channel") {
    val channel = params("channel")
    if(!isValidBaseChannel(channel))
      pass()
    else
      handleGet(channel,params)
  }
  post("/:channel/:action") {
    val channel = params("channel")
    if(!isValidBaseChannel(channel))
      pass()
    else
      handleAction(channel,params)
  }

  get("/game/:gameId") {
    val gameId = params("gameId")
    if(!isValidGameId(gameId))
      pass()
    else {
      val channel = "game/" + gameId
      handleGet(channel,params)
    }
  }
  post("/game/:gameId/:action") {
    val gameId = params("gameId")
    if(!isValidGameId(gameId))
      pass()
    else {
      val channel = "game/" + gameId
      handleAction(channel,params)
    }
  }

  error {
    case e: Throwable => Json.write(IOTypes.SimpleError(e.getMessage()))
  }
}
