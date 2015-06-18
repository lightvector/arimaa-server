package org.playarimaa.server
import scala.util.Try
import scala.util.{Success, Failure}
import org.json4s.{DefaultFormats, Formats, CustomSerializer}
import org.json4s.{JValue, JField, JString, JObject, JInt, JDouble, JBool}
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization

object Json {
  protected lazy val defaultFormats: Formats = DefaultFormats
  protected lazy val plainFormats = Serialization.formats(org.json4s.NoTypeHints)
  protected lazy val stringFormats = {
    Serialization.formats(org.json4s.NoTypeHints) +
    new CustomSerializers.IntSerializer() +
    new CustomSerializers.DoubleSerializer() +
    new CustomSerializers.BooleanSerializer()
  }

  def read[T](jsonString: String)(implicit mf: Manifest[T]): T = {
    JsonMethods.parse(jsonString).extract[T](defaultFormats,mf)
  }
  def extract[T](jValue: JValue)(implicit mf: Manifest[T]): T = {
    jValue.extract[T](defaultFormats,mf)
  }
  def write(src: AnyRef): String = {
    Serialization.write(src)(plainFormats)
  }

  def readFromMap[T](map: Map[String,String])(implicit mf: Manifest[T]): T = {
    val fieldList = map.map {case (k,v) => JField(k, JString(v)) }(collection.breakOut) : List[JField]
    JObject(fieldList).extract[T](stringFormats,mf)
  }
}

object CustomSerializers {
  class IntSerializer extends CustomSerializer[Int](format => ({
    case JInt(x) => x.toInt
    case JString(x) => x.toInt
  },{
    case x: Int => JInt(x)
  }))

  class DoubleSerializer extends CustomSerializer[Double](format => ({
    case JDouble(x) => x.toDouble
    case JString(x) => x.toDouble
  },{
    case x: Double => JDouble(x)
  }))

  class BooleanSerializer extends CustomSerializer[Boolean](format => ({
    case JBool(x) => x
    case JString(x) => x.toBoolean
  },{
    case x: Boolean => JBool(x)
  }))
}
