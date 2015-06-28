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

case class SimpleError(error: String)
case class SimpleResponse(message: String)

case class JoinQuery(channel:String, username: String)
case class JoinResponse(channel:String, username:String, auth: String)
case class LeaveQuery(channel:String, username:String, auth: String)
case class PostQuery(channel:String, username:String, auth: String, text: String)
case class GetQuery(channel:String, minId: Option[Long], minTime: Option[Timestamp], doWait:Boolean)
case class GetResponse(lines: List[ChatLine])

class ChatServlet(system: ActorSystem)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  //Execution context for FutureSupport
  protected implicit def executor: ExecutionContext = system.dispatcher

  val db = Database.forConfig("h2mem1")
  val chat = new Chat(db,system)

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  //curl -i -H "Content-Type: application/json" -X POST -d '{"username":"Bob"}' http://localhost:8080/chat/login
  post("/join") {
    val query = Json.read[JoinQuery](request.body)
    chat.join(query.channel, query.username).map { auth =>
      Json.write(JoinResponse(query.channel,query.username,auth))
    }
  }


  post("/leave") {
    val query = Json.read[LeaveQuery](request.body)
    chat.leave(query.channel, query.username, query.auth) match {
      case Failure(e) => Json.write(SimpleError(e.getMessage()))
      case Success(()) => Json.write(SimpleResponse("Ok"))
    }
  }

  //curl -i -H "Content-Type: application/json" -X POST -d '{"auth":"528aa3ec17260b97ec11a19","text":"Ha"}' http://localhost:8080/chat/
  post("/") {
    val query = Json.read[PostQuery](request.body)
    chat.post(query.channel, query.username, query.auth, query.text) match {
      case Failure(e) => Json.write(SimpleError(e.getMessage()))
      case Success(()) => Json.write(SimpleResponse("Ok"))
    }
  }

  //curl -i -X GET 'http://localhost:8080/chat/?minId=1&doWait=true'
  get("/") {
    val query = Json.readFromMap[GetQuery](params)
    chat.get(query.channel, query.minId, None, query.minTime, None, query.doWait).map { chatLines =>
      Json.write(GetResponse(chatLines))
    }
  }

  error {
    case e: Throwable => Json.write(SimpleError(e.getMessage()))
  }
}
