import org.scalatest.junit.JUnitSuite
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HelloTest extends JUnitSuite {

  @Test
  def testIsEven() {
    assertTrue(Hello.even(2))
    assertFalse(Hello.even(3))
    assertTrue(Hello.odd(3))
  }
}

