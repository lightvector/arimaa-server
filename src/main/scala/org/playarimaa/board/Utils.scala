package org.playarimaa.board
import scala.util.{Try, Success, Failure}

object Utils {
  implicit class ThrowableExtended(val x: Throwable) {
    def tag(tag: String): Throwable =
      new Throwable(tag + " " + x.getMessage, x)
  }
  implicit class TryExtended[T](val x: Try[T]) {
    def tagFailure(tag: => String): Try[T] =
      x.recoverWith { case e: Throwable => Failure(e.tag(tag)) }
  }
}
