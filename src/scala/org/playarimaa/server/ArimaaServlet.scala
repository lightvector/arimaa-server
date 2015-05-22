package org.playarimaa.server
import org.scalatra._
import scalate.ScalateSupport

class ArimaaServlet extends WebAppStack {

  get("/") {
    <html>
      <body>
        <h1>Hello, world!</h1>
      </body>
    </html>
  }

}
