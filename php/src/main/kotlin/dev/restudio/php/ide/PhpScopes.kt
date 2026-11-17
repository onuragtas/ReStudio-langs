package dev.restudio.php.ide

/**
 * A block between `{` and `}` in PHP code.
 *
 * [headerOffset] is the first character of the line that opens the block. When the `{` stands alone on its line,
 * the header is the line before it, for example `public function a()`. [endOffset] is after the `}`.
 */
data class PhpScope(val headerOffset: Int, val openOffset: Int, val endOffset: Int)

/**
 * Finds the blocks of a PHP file without a parser.
 *
 * The scanner skips strings, heredocs, comments and the text outside `<?php ... ?>`. Thus a brace in such text does
 * not open or close a block. A block on one line is not a scope.
 */
object PhpScopes {
  fun find(text: CharSequence): List<PhpScope> {
    val scopes = mutableListOf<PhpScope>()
    val open = ArrayDeque<Int>()
    var i = 0
    var inPhp = false
    val n = text.length
    while (i < n) {
      if (!inPhp) {
        val start = indexOf(text, "<?", i)
        if (start < 0) break
        i = start + 2
        if (text.startsWith("php", i, ignoreCase = true)) i += 3 else if (i < n && text[i] == '=') i++
        inPhp = true
        continue
      }
      val c = text[i]
      when {
        c == '?' && i + 1 < n && text[i + 1] == '>' -> { inPhp = false; i += 2 }
        c == '/' && i + 1 < n && text[i + 1] == '/' -> i = lineCommentEnd(text, i + 2)
        c == '#' && !(i + 1 < n && text[i + 1] == '[') -> i = lineCommentEnd(text, i + 1)
        c == '/' && i + 1 < n && text[i + 1] == '*' -> i = indexOf(text, "*/", i + 2).let { if (it < 0) n else it + 2 }
        c == '\'' || c == '"' || c == '`' -> i = stringEnd(text, i + 1, c)
        c == '<' && text.startsWith("<<<", i) -> i = heredocEnd(text, i + 3)
        c == '{' -> { open.addLast(i); i++ }
        c == '}' -> {
          val start = open.removeLastOrNull()
          if (start != null && !sameLine(text, start, i)) scopes += PhpScope(headerOffset(text, start), start, i + 1)
          i++
        }
        else -> i++
      }
    }
    return scopes.sortedBy { it.openOffset }
  }

  /** Returns the scopes that contain [offset], from the outer one to the inner one. */
  fun around(scopes: List<PhpScope>, offset: Int): List<PhpScope> =
    scopes.filter { it.openOffset < offset && offset < it.endOffset }

  private fun headerOffset(text: CharSequence, brace: Int): Int {
    val lineStart = lineStart(text, brace)
    val first = firstNonBlank(text, lineStart)
    if (first != brace || lineStart == 0) return first
    // The brace stands alone. The header is the previous line that has text.
    var previous = lineStart(text, lineStart - 1)
    while (previous > 0 && firstNonBlank(text, previous) >= lineEnd(text, previous)) previous = lineStart(text, previous - 1)
    val header = firstNonBlank(text, previous)
    return if (header < lineEnd(text, previous)) header else first
  }

  private fun lineStart(text: CharSequence, offset: Int): Int {
    var i = offset
    while (i > 0 && text[i - 1] != '\n') i--
    return i
  }

  private fun lineEnd(text: CharSequence, offset: Int): Int {
    var i = offset
    while (i < text.length && text[i] != '\n') i++
    return i
  }

  private fun firstNonBlank(text: CharSequence, lineStart: Int): Int {
    var i = lineStart
    while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
    return i
  }

  private fun sameLine(text: CharSequence, a: Int, b: Int): Boolean = (a until b).none { text[it] == '\n' }

  /** A line comment ends at the line end or at `?>`. */
  private fun lineCommentEnd(text: CharSequence, from: Int): Int {
    var i = from
    while (i < text.length && text[i] != '\n') {
      if (text[i] == '?' && i + 1 < text.length && text[i + 1] == '>') return i
      i++
    }
    return i
  }

  private fun stringEnd(text: CharSequence, from: Int, quote: Char): Int {
    var i = from
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i += 2
        quote -> return i + 1
        else -> i++
      }
    }
    return text.length
  }

  /** Skips a heredoc or a nowdoc. [from] is after `<<<`. */
  private fun heredocEnd(text: CharSequence, from: Int): Int {
    var i = from
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    if (i < text.length && (text[i] == '\'' || text[i] == '"')) i++
    val start = i
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
    val label = text.subSequence(start, i).toString()
    if (label.isEmpty()) return from
    // The closing label is the first text on a line, and no letter, digit or underscore comes after it.
    var line = lineEnd(text, i) + 1
    while (line < text.length) {
      val first = firstNonBlank(text, line)
      val after = first + label.length
      if (text.startsWith(label, first) && (after >= text.length || !(text[after].isLetterOrDigit() || text[after] == '_'))) {
        return after
      }
      line = lineEnd(text, line) + 1
    }
    return text.length
  }

  private fun indexOf(text: CharSequence, value: String, from: Int): Int {
    var i = from
    while (i <= text.length - value.length) {
      if (text.startsWith(value, i)) return i
      i++
    }
    return -1
  }
}
