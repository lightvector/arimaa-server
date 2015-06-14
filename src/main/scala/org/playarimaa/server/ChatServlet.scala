package org.playarimaa.server
import org.scalatra._
import scalate.ScalateSupport
import java.security.SecureRandom
import scala.util.Try
import scala.util.{Success, Failure}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json._
import org.json4s.jackson.Serialization
import org.playarimaa.server.JsonUtils.TryWithJson

case class ChatLine(username: String, text: String, time: Double, id: Int)

object AuthTokenGen {
  val NUM_SEED_BYTES = 32
  val NUM_TOKEN_INTS = 3

  val secureRand: SecureRandom = new SecureRandom()
  secureRand.setSeed(SecureRandom.getSeed(NUM_SEED_BYTES))

  def genToken: String = {
    List.range(0,NUM_TOKEN_INTS).
      map(_ => secureRand.nextInt).
      map(_.toHexString).
      mkString("")
  }
}

object DoubleTime {
  def get: Double = {
    System.currentTimeMillis.toDouble / 1000.0
  }
}

case class LoginQuery(username: String)
case class LoginResponse(auth: String)
case class LogoutQuery(auth: String)
case class LogoutResponse(message: String)
case class PostQuery(auth: String, text: String)
case class PostResponse(message: String)
case class GetQuery(minId: Option[Int], minTime: Option[Int], doWait:Boolean)
object GetQuery {
  def parse(params: Map[String,String]) : GetQuery =
    GetQuery(
      params.get("minId").map(_.toInt),
      params.get("minTime").map(_.toInt),
      params("doWait").toBoolean
    )
}
case class GetResponse(lines: List[ChatLine])

class ChatServlet extends WebAppStack with JacksonJsonSupport {

  //Sets up automatic case class to JSON output serialization
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private var authToUser: Map[String,String] = Map()
  private var chatLines: List[ChatLine] = List()
  private var nextId: Int = 0

  //Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  post("/login") {
    Json.read[LoginQuery](request.body).map{ query =>
      val auth = AuthTokenGen.genToken
      authToUser = authToUser + (auth -> query.username)
      LoginResponse(auth)
    }.toJsonString
  }

  post("/logout") {
    Json.read[LogoutQuery](request.body).map{ query =>
      authToUser.get(query.auth) match {
        case None => LogoutResponse("Not logged in")
        case Some(_) =>
          authToUser = authToUser - query.auth
          LogoutResponse("Ok")
      }
    }.toJsonString
  }

  post("/") {
    Json.read[PostQuery](request.body).map{ query =>
      authToUser.get(query.auth) match {
        case None => PostResponse("Not logged in")
        case Some(username) =>
          val line = ChatLine(username, query.text, DoubleTime.get, nextId)
          chatLines = line :: chatLines
          nextId = nextId + 1
          PostResponse("Ok")
      }
    }.toJsonString
  }

  get("/") {

    //TODO handle doWait=true
    Json.extract[GetQuery](Json.mapToJson(params)).map{ query =>
      var lines = chatLines
      query.minId.foreach(minId => lines = lines.filter(_.id >= minId))
      query.minTime.foreach(minId => lines = lines.filter(_.time >= minId))
      GetResponse(lines.reverse)
    }.toJsonString
  }

}
