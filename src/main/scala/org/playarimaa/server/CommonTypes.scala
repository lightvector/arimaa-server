package org.playarimaa.server

object CommonTypes {
  type Auth = String
  type SiteAuth = String
  type ChatAuth = String
  type GameAuth = String
  type GameID = String
  type UserID = String
  type Username = String
  type Email = String

  //TODO consider using value classes
  // implicit class Auth(val value: String) extends AnyVal
  // implicit class SiteAuth(val value: String) extends AnyVal
  // implicit class ChatAuth(val value: String) extends AnyVal
  // implicit class GameAuth(val value: String) extends AnyVal
  // implicit class GameID(val value: String) extends AnyVal

  // implicit def authOfSiteAuth(siteAuth: SiteAuth) =
  //   Auth(siteAuth.value)
  // implicit def authOfChatAuth(chatAuth: ChatAuth) =
  //   Auth(chatAuth.value)
  // implicit def authOfGameAuth(gameAuth: GameAuth) =
  //   Auth(gameAuth.value)

  // implicit class Username(val value: String) extends AnyVal with MappedTo[String]
  // implicit class Email(val value: String) extends AnyVal

  // implicit def stringOfUsername(username: Username) =
  //   username.value
  // implicit def stringOfEmail(email: Email) =
  //   email.value
}
