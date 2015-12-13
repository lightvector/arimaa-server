package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.duration.{DurationInt, DurationDouble}
import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import akka.actor.{Scheduler,Cancellable}
import akka.pattern.{after}

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

  //So long as f returns a failed future, try it again after each successive time delay specified
  def withRetry[T](delaySeconds:List[Double], scheduler:Scheduler)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    f.recoverWith { case exn : Throwable =>
      delaySeconds match {
        case Nil => Future.failed(exn)
        case delay :: tail =>
          after(delay seconds,scheduler) {
            withRetry(tail,scheduler)(f)
          }
      }
    }
  }
}
