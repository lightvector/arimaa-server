
//Note that the Scalatra web framework appears to expect this bootstrap file to be in the root package.
import org.scalatra.LifeCycle
import javax.servlet.ServletContext
import akka.actor.ActorSystem
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future, Promise, future}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.slf4j.{Logger, LoggerFactory}
import com.typesafe.config.ConfigFactory

import org.playarimaa.server.DatabaseConfig.driver.api._

import org.playarimaa.server._
import org.playarimaa.server.chat._
import org.playarimaa.server.game._

object ArimaaServerInit {
  private var initialized = false
  def initialize() : Unit = {
    this.synchronized {
      if(!initialized) {
        //Initalize CSPRNG on startup
        RandGen.initialize()
        initialized = true
      }
    }
  }
}

class ScalatraBootstrap extends LifeCycle {
  val actorSystem = ActorSystem()
  val logger =  LoggerFactory.getLogger(getClass)

  def createPool(numThreads: Int): ExecutionContext = {
    new ExecutionContext {
      val threadPool = Executors.newFixedThreadPool(numThreads)
      def execute(runnable: Runnable): Unit = {
        threadPool.submit(runnable)
        ()
      }
      def reportFailure(t: Throwable): Unit = {
        logger.error(t.toString)
      }
    }
  }

  override def init(context: ServletContext): Unit = {
    ArimaaServerInit.initialize()

    val numProcessors = Runtime.getRuntime.availableProcessors

    val config = ConfigFactory.load
    val cryptThreadPoolSize = config.getInt("cryptThreadPoolSize")
    val mainThreadPoolSizeFactor = config.getDouble("mainThreadPoolSizeFactor")
    val siteName = config.getString("siteName")
    val siteAddress = config.getString("siteAddress")
    val smtpHost = config.getString("smtpHost")
    val smtpPort = config.getString("smtpPort")
    val smtpAuth = config.getBoolean("smtpAuth")
    val noReplyAddress = config.getString("noReplyAddress")
    val helpAddress = config.getString("helpAddress")

    val actorEC: ExecutionContext = actorSystem.dispatcher
    val mainEC: ExecutionContext = createPool(math.ceil(numProcessors * mainThreadPoolSizeFactor).toInt)
    val cryptEC: ExecutionContext = createPool(cryptThreadPoolSize)

    //A value that should be unique in practice between each time the server is started
    val serverInstanceID: Long = System.currentTimeMillis

    val db = DatabaseConfig.createDB("h2mem1")
    val scheduler = actorSystem.scheduler
    val emailer = new Emailer(siteName,siteAddress,smtpHost,smtpPort,smtpAuth,noReplyAddress,helpAddress)(mainEC)
    val accounts = new Accounts(db,scheduler)(mainEC)
    val siteLogin = new SiteLogin(accounts,emailer,cryptEC,scheduler)(mainEC)
    val games = new Games(db,siteLogin.logins,scheduler,accounts,serverInstanceID)(mainEC)
    val chat = new ChatSystem(db,siteLogin.logins,actorSystem)(actorEC)

    context.mount(new ArimaaServlet(siteLogin), "/*")
    context.mount(new ChatServlet(accounts,siteLogin,chat,games,scheduler,actorEC), "/api/chat/*")
    context.mount(new AccountServlet(accounts,siteLogin,mainEC), "/api/accounts/*")
    context.mount(new GameServlet(accounts,siteLogin,games,mainEC), "/api/games/*")
  }
}
