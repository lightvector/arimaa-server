
//Note that the Scalatra web framework appears to expect this bootstrap file to be in the root package.
import org.playarimaa.server._
import org.playarimaa.server.game._
import org.scalatra._
import javax.servlet.ServletContext
import _root_.akka.actor.ActorSystem
import scala.concurrent.{ExecutionContext}
import java.util.concurrent.Executors

import java.security._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import slick.driver.H2Driver.api._

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

  override def init(context: ServletContext) {
    ArimaaServerInit.initialize
    context.mount(new ArimaaServlet, "/*")

    val actorEC: ExecutionContext = actorSystem.dispatcher
    val mainEC: ExecutionContext = new ExecutionContext {
      val threadPool = Executors.newFixedThreadPool(4) //TODO don't fix this number
      def execute(runnable: Runnable) {
        threadPool.submit(runnable)
      }
      def reportFailure(t: Throwable) {}
    }

    val db = Database.forConfig("h2mem1")
    val accounts = new Accounts(db)(mainEC)
    val siteLogin = new SiteLogin(accounts)(mainEC)
    val scheduler = actorSystem.scheduler
    val games = new Games(db,siteLogin.logins,scheduler)(mainEC)

    context.mount(new ChatServlet(actorSystem,db,actorEC), "/api/chat/*")
    context.mount(new AccountServlet(siteLogin,mainEC), "/api/accounts/*")
    context.mount(new GameServlet(siteLogin,games,mainEC), "/api/games/*")
  }
}
