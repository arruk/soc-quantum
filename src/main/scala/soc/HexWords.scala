package soc

object HexWords {
  def load(path: String, depth: Int): Seq[BigInt] = {
    val words = Array.fill(depth)(BigInt(0))
    val source = scala.io.Source.fromFile(path)
    var index = 0
    try {
      source.getLines().flatMap(_.split("\\s+")).filter(_.nonEmpty).foreach { token =>
        if (token.startsWith("@")) index = Integer.parseInt(token.drop(1), 16)
        else {
          require(index >= 0 && index < depth, "HEX exceeds memory capacity")
          val value = BigInt(token, 16)
          require(value >= 0 && value.bitLength <= 32, "HEX word exceeds 32 bits")
          words(index) = value
          index += 1
        }
      }
    } finally source.close()
    words.toVector
  }
}
