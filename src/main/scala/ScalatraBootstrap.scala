
//Note that the Scalatra web framework appears to expect this bootstrap file to be in the root package.
import org.playarimaa.server._
import org.scalatra._
import javax.servlet.ServletContext
import _root_.akka.actor.{ActorSystem}

class ScalatraBootstrap extends LifeCycle {
  val system = ActorSystem()

  override def init(context: ServletContext) {

    context.mount(new ArimaaServlet, "/*")
    context.mount(new ChatServlet(system), "/chat/*")
  }
}
