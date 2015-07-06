package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport

import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import slick.driver.H2Driver.api.Database

import org.playarimaa.server.Timestamp.Timestamp

case object ChatServlet {

  case class SimpleError(error: String)

  case object Get {
    case class Query(minId: Option[Long], minTime: Option[Timestamp], doWait: Option[Boolean])
    case class Reply(lines: List[ChatLine])

    def parseQuery(params: Map[String,String]): Query =
      new Query(
        params.get("minId").map(_.toLong),
        params.get("minTime").map(_.toDouble),
        params.get("doWait").map(_.toBoolean)
      )
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
}

import org.playarimaa.server.ChatServlet._

class ChatServlet(system: ActorSystem)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  //Execution context for FutureSupport
  protected implicit def executor: ExecutionContext = system.dispatcher

  val db = Database.forConfig("h2mem1")
  val chat = new ChatSystem(db,system)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  def getAction(action: String): Option[Action] = {
    Action.all.find(_.name == action)
  }

  //curl -i -H "Content-Type: application/json" -X POST -d '{"username":"Bob"}' http://localhost:8080/api/chat/main/login
  //curl -i -H "Content-Type: application/json" -X POST -d '{"username:"Bob","auth":"528aa3ec17260b97ec11a19","text":"Ha"}' http://localhost:8080/api/chat/main/post
  //curl -i -X GET 'http://localhost:8080/api/chat/main?minId=1&doWait=true'

  def handleGet(channel: String, params: Map[String,String]) = {
    val query = Get.parseQuery(params)
    chat.get(channel, query.minId, None, query.minTime, None, query.doWait).map { chatLines =>
      Json.write(Get.Reply(chatLines))
    }
  }
  def handleAction(channel: String, params: Map[String,String]) = {
    getAction(params("action")).get match {
      case Join =>
        val query = Json.read[Join.Query](request.body)
        chat.join(channel, query.username).map { auth =>
          Json.write(Join.Reply(query.username,auth))
        }
      case Leave =>
        val query = Json.read[Leave.Query](request.body)
        chat.leave(channel, query.username, query.auth).map { case () =>
          Json.write(Leave.Reply("Ok"))
        }
      case Post =>
        val query = Json.read[Post.Query](request.body)
        chat.post(channel, query.username, query.auth, query.text).map { case () =>
          Json.write(Post.Reply("Ok"))
        }
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
    if(!isValidBaseChannel(channel) || getAction(params("action")).isEmpty)
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
    if(!isValidGameId(gameId) || getAction(params("action")).isEmpty)
      pass()
    else {
      val channel = "game/" + gameId
      handleAction(channel,params)
    }
  }


  error {
    case e: Throwable => Json.write(SimpleError(e.getMessage()))
  }
}
