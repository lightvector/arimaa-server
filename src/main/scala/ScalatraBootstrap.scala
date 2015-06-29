
//Note that the Scalatra web framework appears to expect this bootstrap file to be in the root package.
import org.playarimaa.server._
import org.scalatra._
import javax.servlet.ServletContext
import _root_.akka.actor.{ActorSystem}

import java.security._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import slick.driver.H2Driver.api._

class ScalatraBootstrap extends LifeCycle {
  val system = ActorSystem()

  override def init(context: ServletContext) {
    //Security.getProviders() foreach { (p: Provider) =>
    //  println(p.getServices())
    //
    AuthTokenGen.initialize

    val db = Database.forConfig("h2mem1")
    Await.result(db.run(DBIO.seq(
      ChatDB.table.schema.create
    )), Duration.Inf)

    context.mount(new ArimaaServlet, "/*")
    context.mount(new ChatServlet(system), "/api/chat/*")
  }
}
