package org.playarimaa.util
/*
 The same as the built-in Option[T], except that:
 1. "None" is called "Error" and also contains a string to report an error message.
 2. "Some" is called "Ok".
 */
sealed trait Result[+T] {
  def flatMap[U](f: T => Result[U]): Result[U] =
    this match {
      case Ok(x) => f(x)
      case err: Error => err
    }

  def bind[U](f: T => Result[U]): Result[U] =
    this.flatMap(f)

  def map[U](f: T => U): Result[U] =
    this.flatMap( x => Ok(f(x)) )

  def flatten[U](implicit conv: Result[T] <:< Result[Result[U]]): Result[U] =
    conv(this).flatMap(identity)

  def isOk: Boolean =
    this match {
      case Ok(_) => true
      case Error(_) => false
    }

  def isError: Boolean =
    !this.isOk
}
case class Ok[T](x: T)        extends Result[T]
case class Error(err: String) extends Result[Nothing]
