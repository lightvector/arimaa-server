object Hello {
  def even(n: Int): Boolean = {
    return n % 2 == 0
  }

  def odd(n: Int): Boolean = {
    return n % 2 == 1
  }

  def main(args: Array[String]) {
    println("Hello, world!")
    println("2 is even: " + even(2))
    println("3 is even: " + even(3))
    println("3 is odd: " + odd(3))
  }
}
