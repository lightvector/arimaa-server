package org.playarimaa.server
import scala.util.Try
import scala.util.{Success, Failure}
import org.json4s.{DefaultFormats, Formats, JValue, JField, JString, JObject}
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization

object Json {
  protected implicit lazy val jsonFormats: Formats = DefaultFormats
  def read[T](jsonString: String)(implicit mf: Manifest[T]): Try[T] = {
    Try(JsonMethods.parse(jsonString).extract[T](jsonFormats,mf))
  }
  def extract[T](jValue: JValue)(implicit mf: Manifest[T]): Try[T] = {
    Try(jValue.extract[T](jsonFormats,mf))
  }
  def write(src: AnyRef): String = {
    implicit val formats = Serialization.formats(org.json4s.NoTypeHints)
    Serialization.write(src)
  }

  def mapToJson(map: Map[String,String]): JValue = {
    val fieldList = map.map {case (k,v) => JField(k, JString(v)) }(collection.breakOut) : List[JField]
    JObject(fieldList)
  }
}

case class SimpleError(error: String)

object JsonUtils {
  //Adds a [toJsonString] function to the scala built-in [Try] class
  implicit class TryWithJson[T <: AnyRef](result:Try[T]) {
    def toJsonString[T]: String = {
      result match {
        case Success(x) => Json.write(x)
        case Failure(err) => Json.write(SimpleError(err.getMessage()))
      }
    }
  }
}
