
//Note that the Scalatra web framework appears to expect this bootstrap file to be in the root package.
import org.playarimaa.server._
import org.scalatra._
import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    context.mount(new ArimaaServlet, "/*")
  }
}
