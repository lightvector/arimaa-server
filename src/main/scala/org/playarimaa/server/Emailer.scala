package org.playarimaa.server
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import java.util.Properties
import javax.mail.{Message,Session,Transport}
import javax.mail.internet.{InternetAddress,MimeMessage}
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server.CommonTypes._

class Emailer(siteName: String, siteAddress: String, smtpHost: String, smtpPort: String, smtpAuth: Boolean, noReplyAddress: String, helpAddress: String)(implicit ec: ExecutionContext) {

  val logger =  LoggerFactory.getLogger(getClass)

  val siteLink : String =
    List("<a href=\"",siteAddress,"\">",siteName,"</a>").mkString("")

  def send(to: Email, subject: String, body: String): Future[Unit] = {
    Future {
      val props = new Properties()
      props.put("mail.smtp.host", smtpHost)
      props.put("mail.smtp.port", smtpPort)
      if(smtpAuth) {
        props.put("mail.smtp.auth", "true")
        props.put("mail.smtp.starttls.enable", "true")
      }

      val session = Session.getDefaultInstance(props, null)
      val message: Message = new MimeMessage(session)
      message.setFrom(new InternetAddress(noReplyAddress))
      message.addRecipient(Message.RecipientType.TO, new InternetAddress(to))
      message.setSubject(subject)
      message.setText(body)
      Transport.send(message)
    }.recover {
      case exn: Exception =>
        logger.error("Error sending email: " + exn)
        throw exn
    }
  }

  def sendPasswordResetRequest(to: Email, username: Username, auth: Auth): Future[Unit] = {
    val resetUrl = siteAddress + "resetPassword/" + username + "/" + auth
    val resetLink = "<a href=\"" + resetUrl + "\">" + resetUrl + "</a>"
    val subject = "Password reset requested"
    val body = List(
      "A password reset was requested for your account \"",
      username,
      "\" at ",
      siteName,
      ". You may choose a new password for your account at the following link:\n",
      resetLink,
      "\n",
      "If you did not make this request or did not intend to request this password reset, please ignore this email."
    ).mkString("")

    send(to,subject,body)
  }

  def sendEmailChangeRequest(to: Email, username: Username, auth: Auth, oldEmail: Email): Future[Unit] = {
    val resetUrl = siteAddress + "confirmChangeEmail/" + username + "/" + auth
    val resetLink = "<a href=\"" + resetUrl + "\">" + resetUrl + "</a>"
    val subject = "Email change requested"
    val body = List(
      "A email address change to this email address from \"",
      oldEmail,
      "\" was requested for your account \"",
      username,
      "\" at ",
      siteName,
      ". You may confirm this change by visiting the following link:\n",
      resetLink,
      "\n",
      "Otherwise, the old address will be retained. If you did not make this request or did not intend to request this change, please ignore this email."
    ).mkString("")

    send(to,subject,body)
  }

  def sendOldEmailChangeNotification(to: Email, username: Username, newEmail: Email): Future[Unit] = {
    val subject = "Email change requested"
    val body = List(
      "The email address for your account \"",
      username,
      "\" at ",
      siteName,
      " was changed from this address to \"",
      newEmail,
      "\". If you did not make this change, please contact ",
      helpAddress,
      "."
    ).mkString("")

    send(to,subject,body)
  }

  def sendPasswordResetNoAccount(to: Email): Future[Unit] = {
    val subject = "Password reset requested for unknown account"
    val body = List(
      "A password reset was requested by you (or someone else) at ",
      siteLink,
      " for this email address. ",
      "However, there is no account with this email address, so no password reset has been initiated. ",
      "If you made this request, please try again with the email address associated with your account. Otherwise, please ignore this email."
    ).mkString("")

    send(to,subject,body)
  }


  def sendVerifyEmail(to: Email, username: Username, auth: Auth): Future[Unit] = {
    val verifyUrl = siteAddress + "verifyEmail/" + username + "/" + auth
    val verifyLink = "<a href=\"" + verifyUrl + "\">" + verifyUrl + "</a>"
    val subject = siteName + " - welcome and please verify your account"
    val body = List(
      "Welcome to ",
      siteLink,
      "! To verify your new account \"",
      username,
      "\" and complete your registration, please visit the following link:\n",
      verifyLink,
      "\n",
      "Otherwise, your account may deactivated or removed within a few days. ",
      "If you did not create this account, please ignore this email, or contact ",
      helpAddress,
      "."
    ).mkString("")

    send(to,subject,body)
  }

}
