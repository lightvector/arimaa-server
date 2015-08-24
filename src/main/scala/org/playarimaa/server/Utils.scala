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
}
