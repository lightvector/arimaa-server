package org.playarimaa.server
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{Accepted, FutureSupport, ScalatraServlet}
import org.scalatra.scalate.ScalateSupport
import java.security.SecureRandom
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe, after}
import akka.util.Timeout
import org.playarimaa.util._


object AuthTokenGen {
  val NUM_SEED_BYTES = 32
  val NUM_TOKEN_INTS = 3

  val secureRand: SecureRandom = new SecureRandom()
  secureRand.setSeed(SecureRandom.getSeed(NUM_SEED_BYTES))

  def genToken: String = {
    this.synchronized {
      List.range(0,NUM_TOKEN_INTS).
        map(_ => secureRand.nextInt).
        map(_.toHexString).
        mkString("")
    }
  }
}

object DoubleTime {
  def get: Double = {
    System.currentTimeMillis.toDouble / 1000.0
  }
}

case class ChatLine(username: String, text: String, time: Double, id: Int)

class Chat {
  var authToUser: Map[String,String] = Map()
  var chatLines: List[ChatLine] = List()
  var nextMessage: Promise[ChatLine] = Promise()
  var nextId: Int = 0

  def join(username: String, auth: String): Unit = this.synchronized {
    authToUser = authToUser + (auth -> username)
  }
  def leave(auth: String): Result[Unit] = this.synchronized {
    authToUser.get(auth) match {
      case None => Error("Not logged in")
      case Some(_) =>
        authToUser = authToUser - auth
        Ok()
    }
  }

  def post(auth: String, text:String): Result[Unit] = this.synchronized {
    authToUser.get(auth) match {
      case None => Error("Not logged in")
      case Some(username) =>
        val line = ChatLine(username, text, DoubleTime.get, nextId)
        chatLines = line :: chatLines
        nextMessage.success(line)
        nextMessage = Promise()
        nextId = nextId + 1
        Ok()
    }
  }

  def get(minId: Option[Int], minTime: Option[Int], doWait: Boolean)
    (implicit ec: ExecutionContext): Future[List[ChatLine]] = this.synchronized {
    var lines = chatLines
    minId.foreach(minId => lines = lines.filter(_.id >= minId))
    minTime.foreach(minId => lines = lines.filter(_.time >= minId))
    if(doWait && lines.isEmpty) {
      nextMessage.future.map{ x =>
        if(minId.exists(minId => x.id >= minId) || minTime.exists(minTime => x.time >= minTime))
          List()
        else
          List(x)
      }
    }
    else {
      val reversed = lines.reverse
      Future(reversed)
    }
  }
}

case class SimpleError(error: String)
case class SimpleResponse(message: String)

case class JoinQuery(username: String)
case class JoinResponse(auth: String)
case class LeaveQuery(auth: String)
case class PostQuery(auth: String, text: String)
case class GetQuery(minId: Option[Int], minTime: Option[Int], doWait:Boolean)
case class GetResponse(lines: List[ChatLine])

class ChatServlet(system: ActorSystem)
    extends WebAppStack with JacksonJsonSupport with FutureSupport {
  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  //Execution context for FutureSupport
  protected implicit def executor: ExecutionContext = system.dispatcher

  //val chatActor = system.actorOf(Props[ChatActor])
  //implicit val timeout = new Timeout(10000)

  val chat = new Chat()

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  //curl -i -H "Content-Type: application/json" -X POST -d '{"username":"Bob"}' http://localhost:8080/chat/login
  post("/login") {
    val query = Json.read[JoinQuery](request.body)
    val auth = AuthTokenGen.genToken
    chat.join(query.username,auth)
    Json.write(JoinResponse(auth))
  }


  post("/logout") {
    val query = Json.read[LeaveQuery](request.body)
    chat.leave(query.auth) match {
      case Error(e) => Json.write(SimpleError(e))
      case Ok(()) => Json.write(SimpleResponse("Ok"))
    }
  }

  //curl -i -H "Content-Type: application/json" -X POST -d '{"auth":"528aa3ec17260b97ec11a19","text":"Ha"}' http://localhost:8080/chat/
  post("/") {
    val query = Json.read[PostQuery](request.body)
    chat.post(query.auth,query.text) match {
      case Error(e) => Json.write(SimpleError(e))
      case Ok(()) => Json.write(SimpleResponse("Ok"))
    }
  }

  //curl -i -X GET 'http://localhost:8080/chat/?minId=1&doWait=true'
  val getTimeoutSecs = 15
  get("/") {
    val query = Json.readFromMap[GetQuery](params)
    val chatLines = chat.get(query.minId, query.minTime, query.doWait)
    val timeout = akka.pattern.after(getTimeoutSecs seconds, system.scheduler)(Future(List()))
    Future.firstCompletedOf(List(chatLines,timeout)).map { lines =>
      Json.write(GetResponse(lines))
    }
  }

  error {
    case e: Throwable => Json.write(SimpleError(e.getMessage()))
  }
}
