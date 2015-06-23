package org.playarimaa.server

/**
 * Indicates an application-level problem.
 * @param message the exception message.
 * @param cause the exception that caused the problem.
 */
class ApplicationException(message: String = null, cause: Throwable = null) extends Exception(message, cause)
