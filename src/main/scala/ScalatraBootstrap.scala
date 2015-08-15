
//Note that the Scalatra web framework appears to expect this bootstrap file to be in the root package.
import org.scalatra.LifeCycle
import javax.servlet.ServletContext
import akka.actor.ActorSystem
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import slick.driver.H2Driver.api._
import org.slf4j.{Logger, LoggerFactory}

import org.playarimaa.server._
import org.playarimaa.server.game._


object ArimaaServerInit {
  private var initialized = false
  def initialize = {
    this.synchronized {
      if(!initialized) {
        //Initalize CSPRNG on startup
        RandGen.initialize

        //Initialize in-memory database and create tables for testing
        val db = Database.forConfig("h2mem1")
        Await.result(db.run(DBIO.seq(
          ( ChatSystem.table.schema ++
            Games.gameTable.schema ++
            Games.movesTable.schema ++
            Accounts.table.schema
          ).create
        )), Duration.Inf)
        initialized = true
      }
    }
  }
}

class ScalatraBootstrap extends LifeCycle {
  val actorSystem = ActorSystem()
  val logger =  LoggerFactory.getLogger(getClass)

  override def init(context: ServletContext) {
    ArimaaServerInit.initialize
    context.mount(new ArimaaServlet, "/*")

    val actorEC: ExecutionContext = actorSystem.dispatcher
    val mainEC: ExecutionContext = new ExecutionContext {
      val threadPool = Executors.newFixedThreadPool(4) //TODO don't fix this number
      def execute(runnable: Runnable) {
        threadPool.submit(runnable)
      }
      def reportFailure(t: Throwable) {
        logger.error(t.toString)
      }
    }

    val db = Database.forConfig("h2mem1")
    val accounts = new Accounts(db)(mainEC)
    val siteLogin = new SiteLogin(accounts)(mainEC)
    val scheduler = actorSystem.scheduler
    val games = new Games(db,siteLogin.logins,scheduler)(mainEC)

    context.mount(new ChatServlet(actorSystem,db,actorEC), "/api/chat/*")
    context.mount(new AccountServlet(siteLogin,mainEC), "/api/accounts/*")
    context.mount(new GameServlet(accounts,siteLogin,games,mainEC), "/api/games/*")
  }
}
