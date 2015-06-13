package org.playarimaa.server
import org.scalatra._
import scalate.ScalateSupport

import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json._

case class Response(message: String, timestamp: Long)
object Response {
  def create(message: String): Response = {
    val timestamp = System.currentTimeMillis()
    Response(message,timestamp)
  }
}

case class Request(a: Int, b:Int)

//TODO: add error handling for malformed input
/*
val errorHandling: PartialFunction[Throwable, Unit] = {
  case e:java.util.NoSuchElementException => send_error("DB malformed", InternalError)
  case e => send_error("Internal Error", InternalError)
}*/

class ArimaaServlet extends WebAppStack with JacksonJsonSupport {
  // Sets up automatic case class to JSON output serialization, required by
  // the JValueResult trait.
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  // Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
  }

  //curl -i http://localhost:8080/
  get("/") {
    Response.create("Hello world")
  }

  //curl -i -X POST -d "name=sally" http://localhost:8080/test
  post("/test") {
    val name = params("name")
    Response.create("Hello " + name)
  }

  //curl -i -H "Content-Type: application/json" -X POST -d '{"a":2, "b":3}' http://localhost:8080/test2
  post("/test2") {
    val r = parse(request.body).extract[Request]
    Response.create("" + (r.a + r.b))
  }


}
