package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.util.{Try, Success, Failure}

object Utils {
  implicit class FutureExtended[T](val future: Future[T]) {
    def resultMap[U](f: Try[T] => U)(implicit ec: ExecutionContext): Future[U] = {
      future.map(x => f(Success(x))).recover{ case x : Throwable => f(Failure(x)) }
    }
    def resultFlatMap[U](f: Try[T] => Future[U])(implicit ec: ExecutionContext): Future[U] = {
      future.flatMap(x => f(Success(x))).recoverWith{ case x : Throwable => f(Failure(x)) }
    }
  }

  implicit class StringExtended(val s: String) extends AnyVal {
    def toFiniteDouble: Double = {
      val x = s.toDouble
      if(x.isNaN || x.isInfinite)
        throw new NumberFormatException("Double value is infinite or nan: " + x)
      else
        x
    }
  }
}
