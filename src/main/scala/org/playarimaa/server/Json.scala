package org.playarimaa.server
import scala.util.Try
import scala.util.{Success, Failure}
import org.json4s.{DefaultFormats, Formats, CustomSerializer}
import org.json4s.{JValue, JField, JString, JObject, JInt, JDouble, JBool}
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization

object Json {
  val formats: Formats = DefaultFormats

  def read[T](jsonString: String)(implicit mf: Manifest[T]): T = {
    JsonMethods.parse(jsonString).extract[T](formats,mf)
  }
  def extract[T](jValue: JValue)(implicit mf: Manifest[T]): T = {
    jValue.extract[T](formats,mf)
  }
  def write(src: AnyRef): String = {
    Serialization.write(src)(formats)
  }
}
